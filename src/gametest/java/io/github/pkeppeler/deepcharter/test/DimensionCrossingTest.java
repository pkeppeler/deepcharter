package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.BreachPayload;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.TowTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for the dimension crossing machinery: the regression net for what ADR 0085 keeps as the base of the dimension seam.
 * They cross between levels of the dimension chain, from the GameTest server's flat overworld (not a campaign level) through the shipped
 * layer dimensions, which {@link TestLayers#layerDimension} reaches: {@code BreachService}'s teleport and its sealed arrival pocket, the
 * fading {@link BreachPayload}, {@link PodTowing} carrying a towed pod with its tower, the chain's links, {@code /deepcharter layer goto}
 * outside the campaign world, and {@link BreachEvents#CROSSED} with {@code from != to}.
 *
 * <p>The seam from the campaign world into a deep dimension below it is not built: it waits on ADR 0085's open calls (whether the seam
 * keeps the fade and the pocket) and on the global depth of #1113. Today a crossing at a layer dimension's floor or top is also a layer
 * boundary; once the seam can fall mid-layer, these tests move to it.
 *
 * <p>Every far chunk a test writes or crosses into is loaded through {@link FarChunks} first, off the server thread. Each test uses its
 * own X and Z, so the columns it carves do not touch another test's.
 */
public class DimensionCrossingTest {
    /** Ticks from a test's setup to its pass: a crossing, then up to 100 ticks of watching. A test still failing then fails. */
    private static final int CROSSING_TICKS = 200;
    /** The test budget: the chunk waits, then the crossing. */
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS + 20;
    /** Ticks a negative case watches for a crossing that must not come. */
    private static final int WATCH_TICKS = 20;
    /** Ticks a sealed pocket must stay air after an arrival. */
    private static final int SEAL_WATCH_TICKS = 100;
    /** Server ticks a pin outlasts its test's {@link #CROSSING_TICKS}, so it never expires while the test still runs. */
    private static final int PIN_MARGIN_TICKS = 20;
    /** Blocks above a level's floor where a faller starts, inside the shaft. */
    private static final int START_HEIGHT = 8;
    /** Blocks of the shaft cut up from a level's floor. */
    private static final int SHAFT_HEIGHT = 10;

    /** One {@link BreachEvents#CROSSED} between two levels, as plain values, so the listener holds no level or entity. */
    private record Crossing(ResourceKey<Level> from, ResourceKey<Level> to, int fromLayer, int toLayer) {}

    /**
     * A place a pod is held in, until it leaves that level or the server tick passes {@code expiresAt}: a pin outlives no test, passed
     * or failed.
     */
    private record Pin(ResourceKey<Level> level, Vec3 at, int expiresAt) {}

    /** Crossings between levels seen per entity UUID. A UUID survives a crossing, so this follows recreated entities. */
    private static final Map<UUID, List<Crossing>> CROSSINGS = new ConcurrentHashMap<>();
    /** Pods held in place by UUID. Pod movement ignores setNoGravity (#554), so the hold is an AFTER_TICK pin. */
    private static final Map<UUID, Pin> PINS = new ConcurrentHashMap<>();

    static {
        BreachEvents.CROSSED.register((entity, from, to, fromLayer, toLayer) -> {
            if (from != to) {
                CROSSINGS
                        .computeIfAbsent(entity.getUUID(), id -> new CopyOnWriteArrayList<>())
                        .add(new Crossing(from.dimension(), to.dimension(), fromLayer, toLayer));
            }
        });
        // Registered after PodTowing's follow, in the same phase, so the pin has the last word.
        PodEvents.AFTER_TICK.register(pod -> {
            Pin pin = PINS.get(pod.getUUID());
            if (pin != null
                    && pod.level() instanceof ServerLevel level
                    && level.getServer().getTickCount() > pin.expiresAt()) {
                PINS.remove(pod.getUUID(), pin);
                return;
            }
            if (pin != null && pod.level().dimension().equals(pin.level())) {
                pod.setPos(pin.at());
                pod.setDeltaMovement(Vec3.ZERO);
            }
        });
    }

    /**
     * The chain's links: the overworld is the surface, the registry counts at least two layers, every loaded level whose type is a layer's
     * is the level the layer's key names, and those layers run from 1 to the count without a gap.
     */
    @GameTest
    public void theChainLinksEachLayerToALevelOfItsOwn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        int count = LayerChain.count(server.registryAccess());
        expect(helper, count >= 2, "the registry holds %d layer dimensions, expected at least 2", count);
        expect(
                helper,
                TestLayers.dimensionBand(server.overworld()).layer() == TestLayers.SURFACE,
                "the flat test overworld is not the surface of the chain");
        TreeSet<Integer> found = new TreeSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            Identifier type =
                    level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier();
            int layer = LayerChain.layerOf(type).orElse(TestLayers.SURFACE);
            if (layer == TestLayers.SURFACE) {
                continue;
            }
            expect(
                    helper,
                    TestLayers.layerDimension(server, layer) == level,
                    "%s has the type of layer %d, but layer %d's key names %s",
                    level.dimension(),
                    layer,
                    layer,
                    TestLayers.layerDimension(server, layer).dimension());
            found.add(layer);
        }
        expect(
                helper,
                found.size() == count && found.first() == 1 && found.last() == count,
                "the loaded layer dimensions are layers %s, expected 1 to %d",
                found,
                count);
        helper.succeed();
    }

    /**
     * A fall through a layer dimension's floor crosses once into the next dimension, at the same X and Z, just under its ceiling, and sends
     * the player a breach payload that fades.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aFallThroughTheFloorCrossesToTheSameXZUnderTheNextCeiling(GameTestHelper helper) {
        double x = 31000.5;
        double z = 31000.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        ServerLevel two = TestLayers.layerDimension(server, 2);
        MockPlayer mock = MockPlayers.join(helper, "crossing-falls");
        mock.announceChannel(BreachPayload.TYPE);
        fall(helper, mock, one, x, z, chunksAround(two, x, z, arrivalReach()), () -> {}, () -> {
            ServerPlayer player = mock.player();
            expectInDimensionOf(helper, player, 2);
            expectSameColumn(helper, player, x, z);
            LayerChain.Band band = TestLayers.dimensionBand(two);
            expect(
                    helper,
                    player.getY() <= band.maxY()
                            && player.getY() >= band.maxY() - LayerTuning.DEFAULT.pocketHeight() - 1,
                    "arrived at y=%s, not just under the ceiling %s",
                    player.getY(),
                    band.maxY());
            expectOneCrossing(helper, player, one, 1, 2);
            List<BreachPayload> sent = mock.payloadsOf(BreachPayload.class);
            expect(
                    helper,
                    sent.size() == 1
                            && sent.getFirst().fades()
                            && sent.getFirst().fromLayer() == 1
                            && sent.getFirst().toLayer() == 2,
                    "expected one fading breach payload from layer 1 to layer 2, got %s",
                    sent);
        });
    }

    /** The flat overworld's floor leads into the first layer dimension. */
    @GameTest(maxTicks = MAX_TICKS)
    public void theSurfaceFloorLeadsIntoTheFirstLayerDimension(GameTestHelper helper) {
        double x = 31100.5;
        double z = 31100.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        MockPlayer mock = MockPlayers.join(helper, "crossing-surface");
        fall(helper, mock, server.overworld(), x, z, chunksAround(one, x, z, arrivalReach()), () -> {}, () -> {
            ServerPlayer player = mock.player();
            expectInDimensionOf(helper, player, 1);
            expectOneCrossing(helper, player, server.overworld(), TestLayers.SURFACE, 1);
        });
    }

    /** Rising above the first layer dimension's top crosses back up to the overworld, just above the crust row, at the same X and Z. */
    @GameTest(maxTicks = MAX_TICKS)
    public void risingAboveTheTopCrossesBackUp(GameTestHelper helper) {
        double x = 31400.5;
        double z = 31400.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        ServerLevel overworld = server.overworld();
        MockPlayer mock = MockPlayers.join(helper, "crossing-rises");
        Vec3 start = new Vec3(x, TestLayers.dimensionBand(one).maxY() + 2, z);
        staged(
                helper,
                one,
                BlockPos.containing(start),
                chunksAround(overworld, x, z, arrivalReach()),
                () -> mock.teleportTo(one, start, 0, 0),
                ticks -> {
                    ServerPlayer player = mock.player();
                    expectInDimensionOf(helper, player, TestLayers.SURFACE);
                    expectSameColumn(helper, player, x, z);
                    int floor = TestLayers.dimensionBand(overworld).minY() + LayerTuning.DEFAULT.crustThickness();
                    expect(
                            helper,
                            player.getY() >= floor && player.getY() <= floor + LayerTuning.DEFAULT.pocketHeight(),
                            "arrived at y=%s, not just above the crust (floor %s)",
                            player.getY(),
                            floor);
                    expectOneCrossing(helper, player, one, 1, TestLayers.SURFACE);
                });
    }

    /** The last layer dimension's floor leads nowhere: a player under it stays in its level, and nothing fires. */
    @GameTest(maxTicks = MAX_TICKS)
    public void theLastLayerDimensionsFloorHasNoCrossing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel deepest = TestLayers.layerDimension(server, LayerChain.count(server.registryAccess()));
        LayerChain.Band band = TestLayers.dimensionBand(deepest);
        Vec3 start = new Vec3(31500.5, band.minY() - 2, 31500.5);
        expectNoCrossing(helper, deepest, start, "crossing-last-floor", player -> player.getY() < band.minY());
    }

    /** The overworld's sky limit leads nowhere: a player above it stays in the overworld, and nothing fires. */
    @GameTest(maxTicks = MAX_TICKS)
    public void theOverworldsSkyLimitHasNoCrossing(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        LayerChain.Band band = TestLayers.dimensionBand(overworld);
        Vec3 start = new Vec3(32200.5, band.maxY() + 2, 32200.5);
        expectNoCrossing(helper, overworld, start, "crossing-sky-limit", player -> player.getY() > band.maxY());
    }

    /**
     * The arrival pocket: rock filling the top of the next dimension is cut to air for the pocket's height under the ceiling, and the
     * open row under it is given a floor of the layer's ground.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void theArrivalPocketIsCutUnderTheCeilingOverAFloor(GameTestHelper helper) {
        double x = 31600.5;
        double z = 31600.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        ServerLevel two = TestLayers.layerDimension(server, 2);
        LayerChain.Band band = TestLayers.dimensionBand(two);
        BlockPos ceiling = BlockPos.containing(x, band.maxY(), z);
        BlockPos arrival = ceiling.below(LayerTuning.DEFAULT.pocketHeight());
        int radius = LayerTuning.DEFAULT.pocketRadius() + 1;
        MockPlayer mock = MockPlayers.join(helper, "crossing-pocket");
        fall(
                helper,
                mock,
                one,
                x,
                z,
                chunksAround(two, x, z, arrivalReach()),
                () -> {
                    // Fill the top of layer 2's dimension, so only a cut pocket can hold the arriving player, then open
                    // the row under the pocket, so only the pocket's own floor can hold it up.
                    RoomCarver.carve(
                            two,
                            ceiling.offset(-radius, -12, -radius),
                            ceiling.offset(radius, 0, radius),
                            Blocks.STONE.defaultBlockState(),
                            Block.UPDATE_ALL);
                    RoomCarver.carve(
                            two,
                            arrival.offset(-radius, -1, -radius),
                            arrival.offset(radius, -1, radius),
                            Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL);
                },
                () -> {
                    ServerPlayer player = mock.player();
                    expectInDimensionOf(helper, player, 2);
                    expectOneCrossing(helper, player, one, 1, 2);
                    BlockPos feet = player.blockPosition();
                    for (int dy = 0; dy < LayerTuning.DEFAULT.pocketHeight(); dy++) {
                        expect(
                                helper,
                                two.getBlockState(feet.above(dy)).isAir(),
                                "the pocket is not clear at %s",
                                feet.above(dy));
                    }
                    expect(
                            helper,
                            two.getBlockState(ceiling).is(Blocks.STONE),
                            "the ceiling itself was cut at %s",
                            ceiling);
                    Block ground = LayerBlocks.groundOf(two, feet.below().getY());
                    expect(
                            helper,
                            two.getBlockState(feet.below()).is(ground),
                            "the open row under the arrival at %s holds %s, not the layer's ground %s",
                            feet.below(),
                            two.getBlockState(feet.below()),
                            ground);
                });
    }

    /** A descent's pocket is sealed: lava and gas on every face of its shell stay out, and the arriving player is not hurt. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aDescendingArrivalPocketIsSealedAgainstLavaAndGas(GameTestHelper helper) {
        double x = 32300.5;
        double z = 32300.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        ServerLevel two = TestLayers.layerDimension(server, 2);
        BlockPos bottom =
                BlockPos.containing(x, TestLayers.dimensionBand(two).maxY() - LayerTuning.DEFAULT.pocketHeight(), z);
        expectSealedArrival(
                helper,
                "crossing-seal-down",
                one,
                new Vec3(x, TestLayers.dimensionBand(one).minY() - 2, z),
                two,
                bottom,
                true);
    }

    /** An ascent's pocket is sealed the same way; its floor is the crust, so it gets no lava under it. */
    @GameTest(maxTicks = MAX_TICKS)
    public void anAscendingArrivalPocketIsSealedAgainstLavaAndGas(GameTestHelper helper) {
        double x = 32400.5;
        double z = 32400.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        ServerLevel two = TestLayers.layerDimension(server, 2);
        BlockPos bottom =
                BlockPos.containing(x, TestLayers.dimensionBand(one).minY() + LayerTuning.DEFAULT.crustThickness(), z);
        expectSealedArrival(
                helper,
                "crossing-seal-up",
                two,
                new Vec3(x, TestLayers.dimensionBand(two).maxY() + 2, z),
                one,
                bottom,
                false);
    }

    /** The pocket never deletes a block entity: a chest in the arrival column is kept, and the arrival moves to a clear column. */
    @GameTest(maxTicks = MAX_TICKS)
    public void anArrivalKeepsABlockEntityInItsPocket(GameTestHelper helper) {
        double x = 31700.5;
        double z = 31700.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        ServerLevel two = TestLayers.layerDimension(server, 2);
        BlockPos chest = BlockPos.containing(
                x, TestLayers.dimensionBand(two).maxY() - LayerTuning.DEFAULT.pocketHeight() + 1, z);
        MockPlayer mock = MockPlayers.join(helper, "crossing-chest");
        fall(
                helper,
                mock,
                one,
                x,
                z,
                chunksAround(two, x, z, arrivalReach()),
                () -> two.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL),
                () -> {
                    ServerPlayer player = mock.player();
                    expectInDimensionOf(helper, player, 2);
                    expectOneCrossing(helper, player, one, 1, 2);
                    expect(
                            helper,
                            two.getBlockState(chest).is(Blocks.CHEST) && two.getBlockEntity(chest) != null,
                            "the crossing deleted the chest at %s",
                            chest);
                    expect(
                            helper,
                            player.getBlockX() != chest.getX() || player.getBlockZ() != chest.getZ(),
                            "the player arrived in the chest's column %s",
                            chest.toShortString());
                });
    }

    /** The pocket never cuts the Conduit's casing: an arrival whose pocket would touch it moves to a clear column beside it. */
    @GameTest(maxTicks = MAX_TICKS)
    public void anArrivalAtTheConduitCasingLeavesItWholeAndArrivesBesideIt(GameTestHelper helper) {
        double x = 31800.5;
        double z = 31800.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        ServerLevel two = TestLayers.layerDimension(server, 2);
        // A slab of casing at the edge of the requested pocket, through its whole height.
        BlockPos requested =
                BlockPos.containing(x, TestLayers.dimensionBand(two).maxY() - LayerTuning.DEFAULT.pocketHeight(), z);
        BlockPos casing = requested.east(LayerTuning.DEFAULT.pocketRadius());
        MockPlayer mock = MockPlayers.join(helper, "crossing-conduit");
        fall(
                helper,
                mock,
                one,
                x,
                z,
                chunksAround(two, x, z, arrivalReach()),
                () -> {
                    for (int dy = -1; dy < LayerTuning.DEFAULT.pocketHeight(); dy++) {
                        two.setBlock(casing.above(dy), ColonyBlocks.CONDUIT.defaultBlockState(), Block.UPDATE_ALL);
                    }
                },
                () -> {
                    ServerPlayer player = mock.player();
                    expectInDimensionOf(helper, player, 2);
                    expectOneCrossing(helper, player, one, 1, 2);
                    expect(
                            helper,
                            player.getBlockX() != requested.getX() || player.getBlockZ() != requested.getZ(),
                            "the player arrived at the requested column %s, whose pocket touches the casing",
                            requested.toShortString());
                    for (int dy = -1; dy < LayerTuning.DEFAULT.pocketHeight(); dy++) {
                        expect(
                                helper,
                                two.getBlockState(casing.above(dy)).is(ColonyBlocks.CONDUIT),
                                "the crossing cut the casing at %s",
                                casing.above(dy));
                    }
                });
    }

    /** A tower that crosses takes its towed pod: a new entity in the next level, still on its cable, at the trail offset. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aTowedPodCrossesWithItsTower(GameTestHelper helper) {
        double x = 31900.5;
        double z = 31900.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        ServerLevel two = TestLayers.layerDimension(server, 2);
        Vec3 start = new Vec3(x, TestLayers.dimensionBand(one).minY() + START_HEIGHT, z);
        MockPlayer mock = MockPlayers.join(helper, "crossing-tower");
        PodEntity[] pods = new PodEntity[2];
        // Wider than the line, so the towed pod's first place, the trail offset, is open: in solid rock trailSpot
        // falls back to the tower's place.
        int gallery = (int) Math.ceil(TowTuning.DEFAULT.trailDistance(Chassis.MOLE, Chassis.MOLE)) + 2;
        staged(
                helper,
                one,
                BlockPos.containing(start),
                chunksAround(two, x, z, gallery + 1),
                () -> {
                    openShaft(one, x, z, 1);
                    mock.teleportTo(one, start, 0, 0);
                    pods[0] = spawnPod(one, start);
                    pods[1] = spawnPod(one, start.add(0, 2, 0));
                    PodTowing.attach(pods[0], pods[1]);
                    expect(
                            helper,
                            mock.player().startRiding(pods[0], true, false),
                            "the mock could not board the tower");
                    // The pocket's rows of air at the arrival.
                    BlockPos arrival = BlockPos.containing(
                            x, TestLayers.dimensionBand(two).maxY() - LayerTuning.DEFAULT.pocketHeight(), z);
                    RoomCarver.carve(
                            two,
                            arrival.offset(-gallery, 0, -gallery),
                            arrival.offset(gallery, LayerTuning.DEFAULT.pocketHeight() - 1, gallery),
                            Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL);
                },
                ticks -> {
                    ServerPlayer player = mock.player();
                    expectInDimensionOf(helper, player, 2);
                    if (!(player.getVehicle() instanceof PodEntity arrived)
                            || !arrived.getUUID().equals(pods[0].getUUID())) {
                        throw failure(
                                helper,
                                "the rider should be on the same tower after crossing, is on %s",
                                player.getVehicle());
                    }
                    if (!(two.getEntity(pods[1].getUUID()) instanceof PodEntity crossed)) {
                        throw failure(helper, "the towed pod did not arrive in layer 2's dimension");
                    }
                    expect(
                            helper,
                            crossed != pods[1] && pods[1].isRemoved(),
                            "expected a new towed pod after the crossing and the old one removed");
                    expect(
                            helper,
                            Optional.of(arrived.getUUID()).equals(PodTowing.towerId(crossed)),
                            "the cable did not cross with the towed pod, got %s",
                            PodTowing.towerId(crossed));
                    double gap = crossed.position().distanceTo(arrived.position());
                    double trail = TowTuning.DEFAULT.trailDistance(arrived, crossed);
                    expect(
                            helper,
                            Math.abs(gap - trail) <= 0.5,
                            "the towed pod should arrive at the trail offset of %s from its tower, it is %s away",
                            trail,
                            gap);
                    expectOneCrossing(helper, player, one, 1, 2);
                    player.stopRiding();
                    arrived.discard();
                    crossed.discard();
                });
    }

    /**
     * A rider in the towed pod crosses with it, and CROSSED fires between the two levels for that rider as for the pilot. The towed pod is
     * pinned above the floor in layer 1, so only its tower's carry can take it across.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aRiderInTheTowedPodCrossesWithIt(GameTestHelper helper) {
        double x = 32000.5;
        double z = 32000.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        ServerLevel two = TestLayers.layerDimension(server, 2);
        Vec3 start = new Vec3(x, TestLayers.dimensionBand(one).minY() + START_HEIGHT, z);
        MockPlayer pilot = MockPlayers.join(helper, "crossing-pilot");
        MockPlayer rider = MockPlayers.join(helper, "crossing-rider");
        PodEntity[] pods = new PodEntity[2];
        staged(
                helper,
                one,
                BlockPos.containing(start),
                chunksAround(two, x, z, arrivalReach() + 8),
                () -> {
                    openShaft(one, x, z, 1);
                    pilot.teleportTo(one, start, 0, 0);
                    rider.teleportTo(one, start.add(0, 2, 0), 0, 0);
                    pods[0] = spawnPod(one, start);
                    pods[1] = spawnPod(one, start.add(0, 2, 0));
                    PodTowing.attach(pods[0], pods[1]);
                    PINS.put(
                            pods[1].getUUID(),
                            new Pin(
                                    one.dimension(),
                                    pods[1].position(),
                                    server.getTickCount() + CROSSING_TICKS + PIN_MARGIN_TICKS));
                    expect(
                            helper,
                            pilot.player().startRiding(pods[0], true, false)
                                    && rider.player().startRiding(pods[1], true, false),
                            "the mocks could not board");
                },
                ticks -> {
                    ServerPlayer seated = rider.player();
                    expectInDimensionOf(helper, seated, 2);
                    if (!(seated.getVehicle() instanceof PodEntity vehicle)
                            || !vehicle.getUUID().equals(pods[1].getUUID())
                            || !PodTowing.isTowed(vehicle)) {
                        throw failure(
                                helper,
                                "the rider should still ride the towed pod on its cable, is on %s",
                                seated.getVehicle());
                    }
                    expectInDimensionOf(helper, pilot.player(), 2);
                    expectOneCrossing(helper, seated, one, 1, 2);
                    expectOneCrossing(helper, pilot.player(), one, 1, 2);
                    PINS.remove(pods[1].getUUID());
                    seated.stopRiding();
                    pilot.player().stopRiding();
                    vehicle.discard();
                    if (pilot.player().level().getEntity(pods[0].getUUID()) instanceof PodEntity tower) {
                        tower.discard();
                    }
                });
    }

    /**
     * Outside the campaign world, goto moves an op to the layer's own dimension, at the same X and Z, inside its band, generating the
     * target chunk when it is not loaded; goto past the last layer fails.
     */
    @GameTest
    public void gotoOutsideTheCampaignWorldMovesAnOpToTheLayersDimension(GameTestHelper helper)
            throws CommandSyntaxException {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(helper, "crossing-goto");
        double x = mock.player().getX();
        double z = mock.player().getZ();
        ServerLevel two = TestLayers.layerDimension(server, 2);
        expect(
                helper,
                two.getChunkSource()
                                .getChunkNow(
                                        mock.player().getBlockX() >> 4,
                                        mock.player().getBlockZ() >> 4)
                        == null,
                "the target chunk in layer 2's dimension is already loaded, so the test proves nothing");
        var source = mock.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
        server.getCommands().getDispatcher().execute("deepcharter layer goto 2", source);
        ServerPlayer player = mock.player();
        expectInDimensionOf(helper, player, 2);
        LayerChain.Band band = TestLayers.dimensionBand(two);
        expect(
                helper,
                player.getY() >= band.minY() + LayerTuning.DEFAULT.crustThickness() && player.getY() <= band.maxY(),
                "goto 2 placed the player at y=%s, outside the layer's dimension",
                player.getY());
        expectSameColumn(helper, player, x, z);
        int past = LayerChain.count(server.registryAccess()) + 1;
        try {
            server.getCommands().getDispatcher().execute("deepcharter layer goto " + past, source);
            throw failure(helper, "goto %d should fail: the chain ends at layer %d", past, past - 1);
        } catch (CommandSyntaxException outOfRange) {
            expect(
                    helper,
                    String.valueOf(outOfRange.getMessage()).contains("No layer " + past),
                    "expected the no-such-layer failure, got %s",
                    String.valueOf(outOfRange.getMessage()));
        }
        helper.succeed();
    }

    /**
     * The shape every crossing test takes. Waits until {@code sourcePos} ticks entities in {@code source} and every chunk of
     * {@code arrival} (all in one level) does too, both off the server thread; then runs {@code setup} once. An entity that crosses
     * into a chunk that is loaded but not entity ticking joins the level's lookup only when the chunk starts ticking, which comes a
     * variable number of ticks after the crossing, so a loaded chunk is not enough. From the next tick it runs {@code check} with
     * the ticks since setup: the test passes on the first tick {@code check} does not throw, and fails with its last
     * failure {@link #CROSSING_TICKS} after setup.
     */
    private static void staged(
            GameTestHelper helper,
            ServerLevel source,
            BlockPos sourcePos,
            List<FarChunks.At> arrival,
            Runnable setup,
            IntConsumer check) {
        boolean[] ticking = {false};
        boolean[] ready = {false};
        long[] setUpAt = {-1};
        FarChunks.awaitEntityTicking(helper, source, sourcePos, () -> ticking[0] = true);
        if (arrival.isEmpty()) {
            ready[0] = true;
        } else {
            ServerLevel arrivalLevel = arrival.getFirst().level();
            FarChunks.awaitAllEntityTicking(
                    helper,
                    arrivalLevel,
                    arrival.stream()
                            .map(chunk -> new ChunkPos(chunk.chunkX(), chunk.chunkZ()).getWorldPosition())
                            .toList(),
                    () -> ready[0] = true);
        }
        TestTicks.each(helper, () -> {
            if (setUpAt[0] < 0) {
                if (ticking[0] && ready[0]) {
                    setup.run();
                    setUpAt[0] = helper.getTick();
                }
                return;
            }
            int ticks = (int) (helper.getTick() - setUpAt[0]);
            try {
                check.accept(ticks);
            } catch (GameTestAssertException notYet) {
                if (ticks < CROSSING_TICKS) {
                    return;
                }
                throw notYet;
            }
            helper.succeed();
        });
    }

    /**
     * Opens a shaft through {@code level}'s floor at the column, puts the mock in it, and drops it a block a tick (a mock has no client
     * gravity) until it leaves the level; {@code setup} prepares the arrival, {@code check} is as for {@link #staged}.
     */
    private static void fall(
            GameTestHelper helper,
            MockPlayer mock,
            ServerLevel level,
            double x,
            double z,
            List<FarChunks.At> arrival,
            Runnable setup,
            Runnable check) {
        LayerChain.Band band = TestLayers.dimensionBand(level);
        Vec3 start = new Vec3(x, band.minY() + START_HEIGHT, z);
        staged(
                helper,
                level,
                BlockPos.containing(start),
                arrival,
                () -> {
                    openShaft(level, x, z, 0);
                    setup.run();
                    mock.teleportTo(level, start, 0, 0);
                },
                ticks -> {
                    ServerPlayer player = mock.player();
                    if (TestLayers.isInDimensionOf(player, band.layer())) {
                        player.setPos(player.getX(), player.getY() - 1, player.getZ());
                    }
                    check.run();
                });
    }

    /** Puts a mock at {@code start} in {@code level} and passes when, {@link #WATCH_TICKS} later, it is still there and nothing fired. */
    private static void expectNoCrossing(
            GameTestHelper helper, ServerLevel level, Vec3 start, String name, Predicate<ServerPlayer> stillOutside) {
        MockPlayer mock = MockPlayers.join(helper, name);
        staged(
                helper,
                level,
                BlockPos.containing(start),
                List.of(),
                () -> mock.teleportTo(level, start, 0, 0),
                ticks -> {
                    ServerPlayer player = mock.player();
                    expect(
                            helper,
                            !CROSSINGS.containsKey(player.getUUID()),
                            "%s crossed: %s",
                            name,
                            CROSSINGS.get(player.getUUID()));
                    expect(
                            helper,
                            player.level() == level && stillOutside.test(player),
                            "the player is at y=%s in %s, expected still past the edge of %s",
                            player.getY(),
                            player.level().dimension(),
                            level.dimension());
                    if (ticks < WATCH_TICKS) {
                        throw failure(helper, "watching %s for %d ticks", name, WATCH_TICKS);
                    }
                });
    }

    /**
     * Lava and gas on every face of the pocket's shell at {@code bottom} in {@code to} (and under it, where its floor is not the crust);
     * a mock that crosses from {@code start} in {@code from} must find the pocket air and stay unhurt for {@link #SEAL_WATCH_TICKS}.
     */
    private static void expectSealedArrival(
            GameTestHelper helper,
            String name,
            ServerLevel from,
            Vec3 start,
            ServerLevel to,
            BlockPos bottom,
            boolean floorIsRock) {
        int radius = LayerTuning.DEFAULT.pocketRadius();
        int height = LayerTuning.DEFAULT.pocketHeight();
        int reach = radius + 1;
        BlockState lava = Blocks.LAVA.defaultBlockState();
        BlockState gas = HazardBlocks.GAS_POCKET.defaultBlockState();
        MockPlayer mock = MockPlayers.join(helper, name);
        long[] arrivedAt = {-1};
        staged(
                helper,
                from,
                BlockPos.containing(start),
                chunksAround(to, bottom.getX() + 0.5, bottom.getZ() + 0.5, arrivalReach()),
                () -> {
                    for (int i = -radius; i <= radius; i += radius) {
                        for (int dy = 0; dy < height; dy += 2) {
                            to.setBlock(bottom.offset(reach, dy, i), lava, Block.UPDATE_CLIENTS);
                            to.setBlock(bottom.offset(-reach, dy, i), lava, Block.UPDATE_CLIENTS);
                            to.setBlock(bottom.offset(i, dy, reach), lava, Block.UPDATE_CLIENTS);
                            to.setBlock(bottom.offset(i, dy, -reach), lava, Block.UPDATE_CLIENTS);
                            to.setBlock(bottom.offset(reach, dy + 1, i), gas, Block.UPDATE_CLIENTS);
                            to.setBlock(bottom.offset(i, dy + 1, -reach), gas, Block.UPDATE_CLIENTS);
                        }
                        to.setBlock(bottom.offset(i, height, 0), lava, Block.UPDATE_CLIENTS);
                        if (floorIsRock) {
                            to.setBlock(bottom.offset(i, -1, 0), lava, Block.UPDATE_CLIENTS);
                        }
                    }
                    mock.teleportTo(from, start, 0, 0);
                },
                ticks -> {
                    ServerPlayer player = mock.player();
                    expect(
                            helper,
                            player.level() == to,
                            "%s is in %s, waiting for the crossing into %s",
                            name,
                            player.level().dimension(),
                            to.dimension());
                    if (arrivedAt[0] < 0) {
                        arrivedAt[0] = helper.getTick();
                    }
                    for (BlockPos pos : BlockPos.betweenClosed(
                            bottom.offset(-radius, 0, -radius), bottom.offset(radius, height - 1, radius))) {
                        if (!to.getBlockState(pos).isAir()) {
                            throw failure(
                                    helper,
                                    "the arrival pocket holds %s at %s %d ticks after arrival",
                                    to.getBlockState(pos).getBlock(),
                                    pos.toShortString(),
                                    helper.getTick() - arrivedAt[0]);
                        }
                    }
                    expect(
                            helper,
                            player.getHealth() >= player.getMaxHealth(),
                            "the arriving player was hurt: health %s",
                            player.getHealth());
                    expectOneCrossing(
                            helper,
                            player,
                            from,
                            TestLayers.dimensionBand(from).layer(),
                            TestLayers.dimensionBand(to).layer());
                    expect(
                            helper,
                            helper.getTick() - arrivedAt[0] >= SEAL_WATCH_TICKS,
                            "watching the pocket, %d ticks after arrival",
                            helper.getTick() - arrivedAt[0]);
                });
    }

    /** Clears {@code level}'s floor at the column, {@code halfWidth} blocks out each way, so whatever is above falls straight out. */
    private static void openShaft(ServerLevel level, double x, double z, int halfWidth) {
        BlockPos column = BlockPos.containing(x, TestLayers.dimensionBand(level).minY(), z);
        RoomCarver.carve(
                level,
                column.offset(-halfWidth, 0, -halfWidth),
                column.offset(halfWidth, SHAFT_HEIGHT, halfWidth),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
    }

    /** Blocks out from an arrival column that the crossing and the test touch: the pocket, its sealed shell and a margin. */
    private static int arrivalReach() {
        return LayerTuning.DEFAULT.pocketRadius() + 3;
    }

    /** Every chunk of {@code level} within {@code reach} blocks of the column, the arrival chunks that {@link #staged} waits on. */
    private static List<FarChunks.At> chunksAround(ServerLevel level, double x, double z, int reach) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        List<FarChunks.At> chunks = new ArrayList<>();
        for (int cx = (bx - reach) >> 4; cx <= (bx + reach) >> 4; cx++) {
            for (int cz = (bz - reach) >> 4; cz <= (bz + reach) >> 4; cz++) {
                chunks.add(new FarChunks.At(level, cx, cz));
            }
        }
        return chunks;
    }

    private static PodEntity spawnPod(ServerLevel level, Vec3 at) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(at);
        level.addFreshEntity(pod);
        return pod;
    }

    private static void expectInDimensionOf(GameTestHelper helper, ServerPlayer player, int layer) {
        expect(
                helper,
                TestLayers.isInDimensionOf(player, layer),
                "%s is at y=%s in %s, expected the dimension of layer %d",
                player.getName().getString(),
                player.getY(),
                player.level().dimension(),
                layer);
    }

    private static void expectSameColumn(GameTestHelper helper, ServerPlayer player, double x, double z) {
        expect(
                helper,
                Math.abs(player.getX() - x) <= 0.5 && Math.abs(player.getZ() - z) <= 0.5,
                "%s is at x=%s z=%s, expected %s %s",
                player.getName().getString(),
                player.getX(),
                player.getZ(),
                x,
                z);
    }

    /** Exactly one CROSSED for {@code player}, from {@code from} to the level it is in now, which is another level. */
    private static void expectOneCrossing(
            GameTestHelper helper, ServerPlayer player, ServerLevel from, int fromLayer, int toLayer) {
        List<Crossing> seen = CROSSINGS.getOrDefault(player.getUUID(), List.of());
        Crossing expected = new Crossing(from.dimension(), player.level().dimension(), fromLayer, toLayer);
        expect(
                helper,
                seen.equals(List.of(expected)),
                "expected one crossing %s for %s, saw %s",
                expected,
                player.getName().getString(),
                seen);
    }
}
