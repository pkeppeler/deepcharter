package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.BreachPayload;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for the dimension crossing machinery: the regression net for what ADR 0085 keeps as the base of the dimension seam.
 * They cross between levels of the dimension chain, from the GameTest server's flat overworld (not a campaign level) through the shipped
 * layer dimensions, which {@link TestLayers#layerDimension} reaches: {@code BreachService}'s teleport and its arrival pocket, the fading
 * {@link BreachPayload}, {@link PodTowing} carrying a towed pod with its tower, the chain's links ({@code LayerChain.dimension},
 * {@code indexOf}, {@code count}), {@code /deepcharter layer goto} outside the campaign world, and {@link BreachEvents#CROSSED} with
 * {@code from != to}.
 *
 * <p>The seam from the campaign world into a deep dimension below it is not built: it waits on ADR 0085's open calls (whether the seam
 * keeps the fade and the pocket) and on the global depth of #1113. Today a crossing at a layer dimension's floor or top is also a layer
 * boundary; once the seam can fall mid-layer, these tests move to it.
 *
 * <p>Each test uses its own X and Z, so the columns it carves do not touch another test's.
 */
public class DimensionCrossingTest {
    /** Ticks a fall, once the chunk ticks, needs to cross and be checked. */
    private static final int CROSSING_TICKS = 200;
    /** Blocks above a level's floor where a faller starts, inside the shaft. */
    private static final int START_HEIGHT = 8;
    /** Blocks of the shaft cut up from a level's floor. */
    private static final int SHAFT_HEIGHT = 10;

    /** One {@link BreachEvents#CROSSED} as plain values, so the listener holds no level or entity. */
    private record Crossing(ResourceKey<Level> from, ResourceKey<Level> to, int fromLayer, int toLayer) {}

    /** Crossings seen per entity UUID. A UUID survives a crossing, so this follows recreated entities. */
    private static final Map<UUID, List<Crossing>> CROSSINGS = new ConcurrentHashMap<>();

    static {
        BreachEvents.CROSSED.register((entity, from, to, fromLayer, toLayer) -> CROSSINGS
                .computeIfAbsent(entity.getUUID(), id -> new CopyOnWriteArrayList<>())
                .add(new Crossing(from.dimension(), to.dimension(), fromLayer, toLayer)));
    }

    /** The chain: the overworld is the surface, each layer up to the registry's count has a loaded level of its own type, and each starts where the one above ends. */
    @GameTest
    public void theChainLinksEachLayerToALevelOfItsOwn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        int count = LayerChain.count(server.registryAccess());
        expect(helper, count >= 2, "the registry holds %d layer dimensions, expected at least 2", count);
        expect(
                helper,
                TestLayers.dimensionBand(server.overworld()).layer() == TestLayers.SURFACE,
                "the flat test overworld is not the surface of the chain");
        for (int layer = 1; layer <= count; layer++) {
            ServerLevel level = TestLayers.layerDimension(server, layer);
            Identifier type =
                    level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier();
            expect(
                    helper,
                    LayerChain.indexOf(type).orElse(-1) == layer,
                    "layer %d resolves to %s, whose type %s is not layer %d",
                    layer,
                    level.dimension(),
                    type,
                    layer);
            LayerChain.Band band = TestLayers.dimensionBand(level);
            expect(helper, band.layer() == layer, "the level of layer %d holds layer %d", layer, band.layer());
            expect(
                    helper,
                    LayerChain.layerCount(level) == count,
                    "the level of layer %d counts %d layers, the registry %d",
                    layer,
                    LayerChain.layerCount(level),
                    count);
            if (layer < count) {
                int next = LayerChain.topDepth(level, layer + 1);
                expect(
                        helper,
                        next == LayerChain.topDepth(level, layer) + band.height(),
                        "layer %d starts at depth %d, not where layer %d ends",
                        layer + 1,
                        next,
                        layer);
            }
        }
        helper.succeed();
    }

    /** A fall through a layer dimension's floor teleports the player to the same X and Z, just under the next dimension's ceiling. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void aFallThroughTheFloorArrivesAtTheSameXZUnderTheNextCeiling(GameTestHelper helper) {
        double x = 31000.5;
        double z = 31000.5;
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(helper, "seam-falls");
        fall(helper, mock, TestLayers.layerDimension(server, 1), x, z);
        helper.succeedWhen(() -> {
            ServerPlayer player = mock.player();
            expectInDimensionOf(helper, player, 2);
            expect(
                    helper,
                    Math.abs(player.getX() - x) <= 0.5 && Math.abs(player.getZ() - z) <= 0.5,
                    "arrived at x=%s z=%s, expected %s %s",
                    player.getX(),
                    player.getZ(),
                    x,
                    z);
            LayerChain.Band two = TestLayers.dimensionBand(player.level());
            expect(
                    helper,
                    player.getY() <= two.maxY() && player.getY() >= two.maxY() - LayerTuning.DEFAULT.pocketHeight() - 1,
                    "arrived at y=%s, not just under the ceiling %s",
                    player.getY(),
                    two.maxY());
        });
    }

    /** The flat overworld's floor leads into the first layer dimension. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void theSurfaceFloorLeadsIntoTheFirstLayerDimension(GameTestHelper helper) {
        double x = 31100.5;
        double z = 31100.5;
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(helper, "seam-surface");
        fall(helper, mock, server.overworld(), x, z);
        helper.succeedWhen(() -> {
            ServerPlayer player = mock.player();
            expectInDimensionOf(helper, player, 1);
            expectOneCrossing(helper, player, server.overworld(), TestLayers.SURFACE, 1);
        });
    }

    /** CROSSED fires once, with the level left as {@code from} and the level arrived in as {@code to}: two levels, not one. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void aCrossingBetweenLevelsFiresCrossedFromOneLevelToTheOther(GameTestHelper helper) {
        double x = 31200.5;
        double z = 31200.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        MockPlayer mock = MockPlayers.join(helper, "seam-crossed");
        fall(helper, mock, one, x, z);
        helper.succeedWhen(() -> {
            ServerPlayer player = mock.player();
            expectInDimensionOf(helper, player, 2);
            expectOneCrossing(helper, player, one, 1, 2);
        });
    }

    /** A crossing between levels sends the player a {@link BreachPayload} that fades, because the player was moved. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void aCrossingBetweenLevelsSendsAFadingPayload(GameTestHelper helper) {
        double x = 31300.5;
        double z = 31300.5;
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(helper, "seam-fades");
        mock.announceChannel(BreachPayload.TYPE);
        fall(helper, mock, TestLayers.layerDimension(server, 1), x, z);
        helper.succeedWhen(() -> {
            expectInDimensionOf(helper, mock.player(), 2);
            List<BreachPayload> sent = mock.payloadsOf(BreachPayload.class);
            expect(helper, sent.size() == 1, "expected one breach payload, got %s", sent);
            BreachPayload payload = sent.getFirst();
            expect(
                    helper,
                    payload.fades() && payload.fromLayer() == 1 && payload.toLayer() == 2,
                    "expected a fading payload from layer 1 to layer 2, got %s",
                    payload);
        });
    }

    /** Rising above the first layer dimension's top crosses back up to the overworld, just above the crust row, at the same X and Z. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void risingAboveTheTopCrossesBackUp(GameTestHelper helper) {
        double x = 31400.5;
        double z = 31400.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        MockPlayer mock = MockPlayers.join(helper, "seam-rises");
        mock.teleportTo(one, new Vec3(x, TestLayers.dimensionBand(one).maxY() + 2, z), 0, 0);
        helper.succeedWhen(() -> {
            ServerPlayer player = mock.player();
            expectInDimensionOf(helper, player, TestLayers.SURFACE);
            expect(
                    helper,
                    Math.abs(player.getX() - x) <= 0.5 && Math.abs(player.getZ() - z) <= 0.5,
                    "arrived at x=%s z=%s, expected %s %s",
                    player.getX(),
                    player.getZ(),
                    x,
                    z);
            int floor = TestLayers.dimensionBand(player.level()).minY() + LayerTuning.DEFAULT.crustThickness();
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
    @GameTest(maxTicks = 40)
    public void theLastLayerDimensionsFloorHasNoCrossing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        int last = LayerChain.count(server.registryAccess());
        ServerLevel deepest = TestLayers.layerDimension(server, last);
        LayerChain.Band band = TestLayers.dimensionBand(deepest);
        MockPlayer mock = MockPlayers.join(helper, "seam-last-floor");
        double x = 31500.5;
        double z = 31500.5;
        mock.teleportTo(deepest, new Vec3(x, band.minY() - 2, z), 0, 0);
        helper.runAfterDelay(20, () -> {
            ServerPlayer player = mock.player();
            expect(helper, !CROSSINGS.containsKey(player.getUUID()), "the last layer's floor crossed");
            expect(
                    helper,
                    player.level() == deepest && player.getY() < band.minY(),
                    "the player is at y=%s in %s, expected under the floor %s of %s",
                    player.getY(),
                    player.level().dimension(),
                    band.minY(),
                    deepest.dimension());
            helper.succeed();
        });
    }

    /** The arrival pocket: rock filling the top of the next dimension is cut to air for the pocket's height, over a floor, under the ceiling. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void theArrivalPocketIsCutUnderTheCeilingOverAFloor(GameTestHelper helper) {
        double x = 31600.5;
        double z = 31600.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel two = TestLayers.layerDimension(server, 2);
        LayerChain.Band twoBand = TestLayers.dimensionBand(two);
        // Fill the whole top of layer 2's dimension, so only a cut pocket can hold the arriving player.
        BlockPos column = BlockPos.containing(x, twoBand.maxY(), z);
        int radius = LayerTuning.DEFAULT.pocketRadius() + 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 0; dy <= 12; dy++) {
                    two.setBlock(column.offset(dx, -dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        MockPlayer mock = MockPlayers.join(helper, "seam-pocket");
        fall(helper, mock, TestLayers.layerDimension(server, 1), x, z);
        helper.succeedWhen(() -> {
            expectInDimensionOf(helper, mock.player(), 2);
            BlockPos feet = mock.player().blockPosition();
            for (int dy = 0; dy < LayerTuning.DEFAULT.pocketHeight(); dy++) {
                expect(
                        helper,
                        two.getBlockState(feet.above(dy)).isAir(),
                        "the pocket is not clear at %s",
                        feet.above(dy));
            }
            expect(helper, two.getBlockState(column).is(Blocks.STONE), "the ceiling itself was cut at %s", column);
            expect(helper, !two.getBlockState(feet.below()).isAir(), "no floor under the arrival at %s", feet.below());
        });
    }

    /** The pocket never deletes a block entity: a chest in the arrival column is kept, and the arrival moves to a clear column. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void anArrivalKeepsABlockEntityInItsPocket(GameTestHelper helper) {
        double x = 31700.5;
        double z = 31700.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel two = TestLayers.layerDimension(server, 2);
        BlockPos chest = BlockPos.containing(
                x, TestLayers.dimensionBand(two).maxY() - LayerTuning.DEFAULT.pocketHeight() + 1, z);
        two.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        MockPlayer mock = MockPlayers.join(helper, "seam-chest");
        fall(helper, mock, TestLayers.layerDimension(server, 1), x, z);
        helper.succeedWhen(() -> {
            expectInDimensionOf(helper, mock.player(), 2);
            expect(
                    helper,
                    two.getBlockState(chest).is(Blocks.CHEST) && two.getBlockEntity(chest) != null,
                    "the crossing deleted the chest at %s",
                    chest);
        });
    }

    /** The pocket never cuts the Conduit's casing: an arrival whose pocket would touch it moves to a clear column beside it. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void anArrivalAtTheConduitCasingLeavesItWholeAndArrivesBesideIt(GameTestHelper helper) {
        double x = 31800.5;
        double z = 31800.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel two = TestLayers.layerDimension(server, 2);
        LayerChain.Band twoBand = TestLayers.dimensionBand(two);
        // A slab of casing at the edge of the requested pocket, through its whole height.
        BlockPos requested = BlockPos.containing(x, twoBand.maxY() - LayerTuning.DEFAULT.pocketHeight(), z);
        BlockPos casing = requested.east(LayerTuning.DEFAULT.pocketRadius());
        for (int dy = -1; dy < LayerTuning.DEFAULT.pocketHeight(); dy++) {
            two.setBlock(casing.above(dy), ColonyBlocks.CONDUIT.defaultBlockState(), Block.UPDATE_ALL);
        }
        MockPlayer mock = MockPlayers.join(helper, "seam-conduit");
        fall(helper, mock, TestLayers.layerDimension(server, 1), x, z);
        helper.succeedWhen(() -> {
            ServerPlayer player = mock.player();
            expectInDimensionOf(helper, player, 2);
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
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void aTowedPodCrossesWithItsTower(GameTestHelper helper) {
        double x = 31900.5;
        double z = 31900.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        int floor = TestLayers.dimensionBand(one).minY();
        openShaft(one, x, z, 1);
        MockPlayer mock = MockPlayers.join(helper, "seam-tower");
        mock.teleportTo(one, new Vec3(x, floor + START_HEIGHT, z), 0, 0);
        PodEntity[] pods = {null, null};
        FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, floor + START_HEIGHT, z), () -> {
            pods[0] = spawnPod(one, x, floor + START_HEIGHT, z);
            pods[1] = spawnPod(one, x, floor + START_HEIGHT + 2, z);
            PodTowing.attach(pods[0], pods[1]);
            expect(helper, mock.player().startRiding(pods[0], true, false), "the mock could not board the tower");
            // The towed pod arrives at the trail offset only where it fits, and in its tower's place in rock, so the
            // arrival is a gallery wider than the line, floored level with the pocket's floor.
            openGalleryAtTheArrival(
                    TestLayers.layerDimension(server, 2),
                    x,
                    z,
                    (int) Math.ceil(TowTuning.DEFAULT.trailDistance(pods[0], pods[1])) + 2);
        });
        helper.succeedWhen(() -> {
            expect(helper, pods[0] != null, "waiting for the chunk at x=%s z=%s to tick entities", x, z);
            ServerPlayer player = mock.player();
            expectInDimensionOf(helper, player, 2);
            if (!(player.getVehicle() instanceof PodEntity arrived)
                    || !arrived.getUUID().equals(pods[0].getUUID())) {
                throw failure(
                        helper, "the rider should be on the same tower after crossing, is on %s", player.getVehicle());
            }
            ServerLevel two = player.level();
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
        });
    }

    /** A rider in the towed pod crosses with it, and CROSSED fires between the two levels for that rider as for the pilot. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void aRiderInTheTowedPodCrossesWithIt(GameTestHelper helper) {
        double x = 32000.5;
        double z = 32000.5;
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.layerDimension(server, 1);
        int floor = TestLayers.dimensionBand(one).minY();
        openShaft(one, x, z, 1);
        MockPlayer pilot = MockPlayers.join(helper, "seam-pilot");
        MockPlayer rider = MockPlayers.join(helper, "seam-rider");
        pilot.teleportTo(one, new Vec3(x, floor + START_HEIGHT, z), 0, 0);
        rider.teleportTo(one, new Vec3(x, floor + START_HEIGHT + 2, z), 0, 0);
        PodEntity[] pods = {null, null};
        FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, floor + START_HEIGHT, z), () -> {
            pods[0] = spawnPod(one, x, floor + START_HEIGHT, z);
            pods[1] = spawnPod(one, x, floor + START_HEIGHT + 2, z);
            PodTowing.attach(pods[0], pods[1]);
            expect(
                    helper,
                    pilot.player().startRiding(pods[0], true, false)
                            && rider.player().startRiding(pods[1], true, false),
                    "the mocks could not board");
        });
        helper.succeedWhen(() -> {
            expect(helper, pods[0] != null, "waiting for the chunk at x=%s z=%s to tick entities", x, z);
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
        });
    }

    /** Outside the campaign world, goto moves an op to the layer's own dimension, at the same X and Z, standing inside it. */
    @GameTest
    public void gotoOutsideTheCampaignWorldMovesAnOpToTheLayersDimension(GameTestHelper helper)
            throws CommandSyntaxException {
        MinecraftServer server = helper.getLevel().getServer();
        double x = 32100.5;
        double z = 32100.5;
        MockPlayer mock = MockPlayers.join(helper, "seam-goto");
        try {
            ServerLevel overworld = server.overworld();
            mock.teleportTo(
                    overworld, new Vec3(x, TestLayers.dimensionBand(overworld).maxY() - 4, z), 0, 0);
            var source = mock.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
            server.getCommands().getDispatcher().execute("deepcharter layer goto 2", source);
            ServerPlayer player = mock.player();
            expectInDimensionOf(helper, player, 2);
            LayerChain.Band band = TestLayers.dimensionBand(player.level());
            expect(
                    helper,
                    player.getY() >= band.minY() + LayerTuning.DEFAULT.crustThickness() && player.getY() <= band.maxY(),
                    "goto 2 placed the player at y=%s, outside the layer's dimension",
                    player.getY());
            expect(
                    helper,
                    Math.abs(player.getX() - x) <= 0.5 && Math.abs(player.getZ() - z) <= 0.5,
                    "goto 2 moved the player to x=%s z=%s, expected %s %s",
                    player.getX(),
                    player.getZ(),
                    x,
                    z);
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
        } finally {
            mock.leave();
        }
        helper.succeed();
    }

    /** Opens a shaft through {@code level}'s floor at the column, puts the mock in it, and drops it a block a tick until it leaves. */
    private static void fall(GameTestHelper helper, MockPlayer mock, ServerLevel level, double x, double z) {
        openShaft(level, x, z, 0);
        mock.teleportTo(level, new Vec3(x, TestLayers.dimensionBand(level).minY() + START_HEIGHT, z), 0, 0);
        int layer = TestLayers.dimensionBand(level).layer();
        // A mock has no client gravity, so the test moves it, once its chunk ticks.
        boolean[] ticking = {false};
        FarChunks.awaitEntityTicking(helper, level, mock.player().blockPosition(), () -> ticking[0] = true);
        TestTicks.each(helper, () -> {
            ServerPlayer player = mock.player();
            if (ticking[0] && TestLayers.isInDimensionOf(player, layer)) {
                player.setPos(player.getX(), player.getY() - 1, player.getZ());
            }
        });
    }

    /** Clears {@code level}'s floor at the column, {@code halfWidth} blocks out each way, so whatever is above falls straight out. */
    private static void openShaft(ServerLevel level, double x, double z, int halfWidth) {
        int floor = TestLayers.dimensionBand(level).minY();
        BlockPos column = BlockPos.containing(x, floor, z);
        RoomCarver.carve(
                level,
                column.offset(-halfWidth, 0, -halfWidth),
                column.offset(halfWidth, SHAFT_HEIGHT, halfWidth),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
    }

    /**
     * Opens a gallery where a descent into {@code level} arrives at the column: the pocket's rows of air under the ceiling,
     * {@code halfWidth} blocks out each way, over a stone floor level with the pocket's. The ceiling row itself stays.
     */
    private static void openGalleryAtTheArrival(ServerLevel level, double x, double z, int halfWidth) {
        int ceiling = TestLayers.dimensionBand(level).maxY();
        BlockPos arrival = BlockPos.containing(x, ceiling - LayerTuning.DEFAULT.pocketHeight(), z);
        RoomCarver.carve(
                level,
                arrival.offset(-halfWidth, -1, -halfWidth),
                arrival.offset(halfWidth, -1, halfWidth),
                Blocks.STONE.defaultBlockState(),
                Block.UPDATE_ALL);
        RoomCarver.carve(
                level,
                arrival.offset(-halfWidth, 0, -halfWidth),
                arrival.offset(halfWidth, LayerTuning.DEFAULT.pocketHeight() - 1, halfWidth),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
    }

    private static PodEntity spawnPod(ServerLevel level, double x, double y, double z) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(x, y, z);
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

    /** Exactly one CROSSED for {@code player}, from {@code from} to the level it is in now, which is another level. */
    private static void expectOneCrossing(
            GameTestHelper helper, ServerPlayer player, ServerLevel from, int fromLayer, int toLayer) {
        List<Crossing> seen = CROSSINGS.getOrDefault(player.getUUID(), List.of());
        Crossing expected = new Crossing(from.dimension(), player.level().dimension(), fromLayer, toLayer);
        expect(
                helper,
                seen.equals(List.of(expected)) && !expected.from().equals(expected.to()),
                "expected one crossing %s for %s, saw %s",
                expected,
                player.getName().getString(),
                seen);
    }
}
