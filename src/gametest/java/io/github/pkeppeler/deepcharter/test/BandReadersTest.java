package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.SPRINT;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.pod.CockpitReading;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgress;
import io.github.pkeppeler.deepcharter.handbook.HandbookTuning;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.CampaignBands.Band;
import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.RoomSeal;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrders;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.outpost.OutpostBuilder;
import io.github.pkeppeler.deepcharter.outpost.OutpostData;
import io.github.pkeppeler.deepcharter.outpost.OutpostRates;
import io.github.pkeppeler.deepcharter.outpost.OutpostTuning;
import io.github.pkeppeler.deepcharter.pod.PodBrace;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for the readers that key on the band at a Y and not on the level (#799, ADR 0059), in
 * {@code deepcharter:campaign_sample}, where the surface and both layers are Y bands of one level. The layer dimensions keep their own
 * tests: in them the band is the whole level.  The figures and the structures have their own classes
 * ({@code BandFiguresTest}, {@code BandStructuresTest}).
 */
public class BandReadersTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1000;
    private static final int Z = 23000;
    private static final float ROW = 8f;
    private static final Band ONE = CampaignBands.LAYER_1;
    private static final Band TWO = CampaignBands.LAYER_2;
    /** The deepest layer the campaign world builds: its floor crust leads nowhere. */
    private static final Band LAST = CampaignBands.layer(CampaignBands.lastLayer());

    // ---- PodDrill.crustLeadsOn ----

    /** A mock pilot in a new pod at {@code feet}, over a column of crust: the rows {@code crustRows} high from {@code floor}, then air above. */
    private static final class Bore {
        final MockPlayer pilot;

        @Nullable
        PodEntity pod;

        float hullBefore = Float.NaN;

        Bore(GameTestHelper helper, int x, int floor, int crustRows, int feetAbove) {
            ServerLevel level = helper.getLevel();
            Vec3 at = new Vec3(x, floor + feetAbove, Z);
            pilot = MockPlayers.join(helper, "band-bore-" + Math.abs(floor));
            pilot.teleportTo(level, at, 0f, 0f);
            FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
                // Carved once the column's chunks are loaded: RoomSeal reads the shell, which would load them on the
                // server thread.
                RoomCarver.carve(
                        level, x - 2, x + 2, floor, floor + crustRows - 1, Z - 2, Z + 2, LayerBlocks.BREACH_CRUST);
                RoomCarver.carve(
                        level, x - 2, x + 2, floor + crustRows, floor + crustRows + 7, Z - 2, Z + 2, Blocks.AIR);
                PodEntity made = TestEntities.create(PodRegistry.POD, level);
                made.setPos(at);
                level.addFreshEntity(made);
                if (!pilot.player().startRiding(made)) {
                    throw failure(helper, "the pilot could not mount the pod");
                }
                hullBefore = made.hull();
                pilot.setInput(SPRINT);
                pod = made;
            });
        }
    }

    private static int crustCells(ServerLevel level, int x, int y) {
        int found = 0;
        for (BlockPos pos : BlockPos.betweenClosed(x - 1, y, Z - 1, x, y, Z)) {
            if (level.getBlockState(pos).is(LayerBlocks.BREACH_CRUST)) {
                found++;
            }
        }
        return found;
    }

    /** Layer 1's floor crust leads on to layer 2: the drill bores its last row, and it costs the hull a row of crust. */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theCrustAtLayer1sFloorIsBored(GameTestHelper helper) {
        int x = 23000;
        int floor = ONE.minY();
        ServerLevel level = helper.getLevel();
        Bore bore = new Bore(helper, x, floor, 1, 1);
        TestTicks.each(helper, () -> {
            PodEntity pod = bore.pod;
            if (pod == null || crustCells(level, x, floor) == 4) {
                if (pod != null && pod.tickCount > 200) {
                    throw failure(helper, "the pod did not bore the crust at layer 1's floor in 200 ticks");
                }
                return;
            }
            bore.pilot.releaseInput();
            float cost = PodStats.of(pod).crustHullDamage();
            expect(
                    helper,
                    Math.abs(bore.hullBefore - pod.hull() - cost) < 0.01f,
                    "boring a crust row should cost %s hull, the pod went from %s to %s",
                    cost,
                    bore.hullBefore,
                    pod.hull());
            pod.discard();
            helper.succeed();
        });
    }

    /** The last layer's floor crust leads nowhere (unbuilt depth lies below it), so the pod stands on it and does not drill. */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theCrustAtTheLastLayersFloorIsRefused(GameTestHelper helper) {
        // Layer 1's bore is in this column too: the layers share a level, so one set of chunks serves both.
        int x = 23000;
        int floor = LAST.minY();
        ServerLevel level = helper.getLevel();
        Bore bore = new Bore(helper, x, floor, 3, 3);
        TestTicks.each(helper, () -> {
            PodEntity pod = bore.pod;
            if (pod == null) {
                return;
            }
            expect(
                    helper,
                    !pod.drilling() && crustCells(level, x, floor + 2) == 4,
                    "the pod drills the crust at the last layer's floor, which leads to unbuilt depth");
            if (pod.tickCount < 100) {
                return;
            }
            expect(
                    helper,
                    pod.onGround() && crustCells(level, x, floor) == 4,
                    "the pod should stand on intact crust, it is at %s",
                    pod.position());
            pod.discard();
            helper.succeed();
        });
    }

    // ---- PodBrace.ahead ----

    /** A Mole with its feet at {@code y} in the campaign level, not added to the world, so it never ticks. */
    private static PodEntity pod(ServerLevel level, int y, float hull) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(23128, y, Z);
        pod.setHull(hull);
        pod.setOnGround(true);
        return pod;
    }

    /**
     * Above layer 1's floor the crust warning counts from that band's floor: 7 slabs above its 3 crust rows, in the crust, and out of reach
     * higher up, and the same above layer 2's floor, which leads on to layer 3 (#972). The last layer's floor has no warning (nothing is bored
     * there), nor has the surface.
     */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void theBraceWarnsAboveEachBandFloorThatLeadsOn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int crust = LayerTuning.DEFAULT.crustThickness();
        PodBrace.Warning above = PodBrace.status(pod(level, ONE.minY() + crust + 7, 20f), true)
                .warning()
                .orElseThrow(() -> failure(helper, "no crust warning 7 slabs above layer 1's floor"));
        expect(
                helper,
                above.slabsAway() == 7 && above.crustSlabs() == crust && Math.abs(above.cost() - crust * ROW) < 0.01f,
                "the warning above layer 1's floor is %s",
                above);
        PodBrace.Warning inside = PodBrace.status(pod(level, ONE.minY() + 1, ROW), true)
                .warning()
                .orElseThrow(() -> failure(helper, "no crust warning in layer 1's crust"));
        expect(helper, inside.slabsAway() == 0 && inside.crustSlabs() == 1, "the warning in the crust is %s", inside);
        expect(
                helper,
                PodBrace.status(pod(level, ONE.minY() + crust + 100, 1f), true)
                        .warning()
                        .isEmpty(),
                "a warning far above layer 1's floor");
        expect(
                helper,
                PodBrace.status(pod(level, TWO.minY() + crust + 7, 20f), true)
                        .warning()
                        .filter(warning -> warning.slabsAway() == 7)
                        .isPresent(),
                "no crust warning 7 slabs above layer 2's floor, which leads on to layer 3");
        expect(
                helper,
                PodBrace.status(pod(level, LAST.minY() + crust + 7, 1f), true)
                        .warning()
                        .isEmpty(),
                "a warning above the last layer's floor, whose crust is not drilled");
        expect(
                helper,
                PodBrace.status(pod(level, CampaignBands.SURFACE.minY() + crust + 7, 1f), true)
                        .warning()
                        .isEmpty(),
                "a warning above the surface's floor, which has no crust");
        helper.succeed();
    }

    // ---- OutpostRates and OutpostBuilder ----

    /**
     * Carves the hall above {@code floor}: RoomSeal reads the shell, which would load the chunks on the server thread
     * unless they are loaded already.
     */
    private static void carveHall(ServerLevel level, BlockPos floor) {
        RoomCarver.carve(
                level,
                floor.getX() - 8,
                floor.getX() + 8,
                floor.getY() + 1,
                floor.getY() + 16,
                Z - 8,
                Z + 8,
                Blocks.STONE);
    }

    /**
     * Joins a survival player who has founded a charter; the hall is carved by {@link #carveHall} once its chunks are
     * loaded.
     */
    private static MockPlayer foundedBuilderBesideHall(GameTestHelper helper, ServerLevel level, String name) {
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.player().setGameMode(GameType.SURVIVAL);
        if (Charters.found(
                        level.getServer(),
                        mock.player().getUUID(),
                        name + " " + UUID.randomUUID().toString().substring(0, 8))
                .isPresent()) {
            throw failure(helper, "founding the charter should succeed");
        }
        return mock;
    }

    /** An outpost is built at the crust top of layer 1's band, and its rates apply by the band of the terminal's Y. */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void anOutpostIsBuiltInLayer1sBandAndPricesByBand(GameTestHelper helper) {
        int x = 23192;
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos floor = new BlockPos(x, ONE.minY() + LayerTuning.DEFAULT.crustThickness() - 1, Z);
        MockPlayer mock = foundedBuilderBesideHall(helper, level, "band-outposter");
        CharterId charter = Charters.charterOfOrThrow(server, mock.player().getUUID())
                .orElseThrow()
                .id();
        FarChunks.awaitEntityTicking(
                helper,
                level,
                floor,
                () -> WorldData.with(server, OutpostData.TYPE, new OutpostData(), () -> {
                    expect(
                            helper,
                            mock.player().isAlive(),
                            "the builder died while it waited at %s for the chunk",
                            helper.absoluteVec(MockPlayers.STRUCTURE_SPAWN));
                    carveHall(level, floor);
                    // On the build tick, so the far-chunk wait cannot suffocate the builder in the carved rock.
                    mock.teleportTo(level, Vec3.atBottomCenterOf(floor.above()), 0f, 0f);
                    Optional<Component> refusal = OutpostBuilder.build(mock.player(), level, floor, Direction.UP);
                    if (refusal.isPresent()) {
                        // entity-query: names what refused the kit, in the chunk this test forced and waited on
                        List<String> inShell = level.getEntities(mock.player(), new AABB(floor).inflate(8)).stream()
                                .map(entity -> entity.getType().toShortString() + " " + entity.getUUID() + " tick "
                                        + entity.tickCount + " at " + entity.blockPosition()
                                        + (entity instanceof ItemEntity item ? " holding " + item.getItem() : ""))
                                .toList();
                        throw failure(
                                helper,
                                "the outpost in layer 1's band was refused: %s; entities near the hall: %s; the builder is %s (removed %s, health %s) at %s; game time %s",
                                refusal.get().getString(),
                                inShell,
                                mock.player().isAlive() ? "alive" : "dead",
                                mock.player().isRemoved(),
                                mock.player().getHealth(),
                                mock.player().blockPosition(),
                                level.getGameTime());
                    }
                    expect(
                            helper,
                            OutpostData.get(server).of(charter, 1).isPresent(),
                            "the outpost is not recorded in layer 1");
                    expect(
                            helper,
                            level.getBlockState(floor.offset(5, 1, 0)).is(ColonyKit.RIVETED_PLATE),
                            "the hall's wall is %s",
                            level.getBlockState(floor.offset(5, 1, 0)));
                    long colony = FuelTuning.DEFAULT.pricePerLitre();
                    expect(
                            helper,
                            OutpostRates.fuelPricePerLitre(level, floor.getY())
                                    == colony * OutpostTuning.DEFAULT.fuelPriceFactor(),
                            "a litre in layer 1 costs %s",
                            OutpostRates.fuelPricePerLitre(level, floor.getY()));
                    expect(
                            helper,
                            OutpostRates.fuelPricePerLitre(level, CampaignBands.SURFACE.minY() + 100) == colony,
                            "a litre on the surface costs %s",
                            OutpostRates.fuelPricePerLitre(level, CampaignBands.SURFACE.minY() + 100));
                    helper.succeed();
                }));
    }

    /** A builder that dies in the rock of the hall drops its starting rations there, and the kit sweeps them rather than refusing: the rations end up in the builder's inventory and none lie in the hall. */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aBuilderWhoDiesInTheHallHasItsRationsSweptNotRefused(GameTestHelper helper) {
        int x = 23704;
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos floor = new BlockPos(x, ONE.minY() + LayerTuning.DEFAULT.crustThickness() - 1, Z);
        MockPlayer mock = foundedBuilderBesideHall(helper, level, "band-dying-outposter");
        FarChunks.awaitEntityTicking(
                helper,
                level,
                floor,
                () -> WorldData.with(server, OutpostData.TYPE, new OutpostData(), () -> {
                    carveHall(level, floor);
                    mock.teleportTo(level, Vec3.atBottomCenterOf(floor.above()), 0f, 0f);
                    mock.player().kill(level);
                    // entity-query: counts what lies in the hall, in the chunk this test forced and waited on
                    AABB hall = new AABB(floor).inflate(6, 6, 6);
                    int lying = level.getEntitiesOfClass(ItemEntity.class, hall).size();
                    expect(helper, lying > 0, "the dead builder should have dropped its rations in the hall");
                    Optional<Component> refusal = OutpostBuilder.build(mock.player(), level, floor, Direction.UP);
                    expect(
                            helper,
                            refusal.isEmpty(),
                            "the rations should be swept, not refused, it said %s",
                            refusal.map(Component::getString).orElse(""));
                    expect(
                            helper,
                            level.getEntitiesOfClass(ItemEntity.class, hall).isEmpty(),
                            "no ration should lie in the hall after the sweep");
                    helper.succeed();
                }));
    }

    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void theKitIsRefusedOnTheSurfaceBandAndInLayer2(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MockPlayer mock = MockPlayers.join(helper, "band-refused");
        for (int y :
                new int[] {CampaignBands.SURFACE.minY() + 100, TWO.minY() + LayerTuning.DEFAULT.crustThickness() - 1}) {
            Optional<Component> refusal =
                    OutpostBuilder.build(mock.player(), level, new BlockPos(23256, y, Z), Direction.UP);
            String said = refusal.map(message -> message.getContents() instanceof TranslatableContents translatable
                            ? translatable.getKey()
                            : message.getString())
                    .orElse("nothing: it built");
            expect(
                    helper,
                    said.equals("message.deepcharter.outpost.not_here"),
                    "the kit at y=%d should be refused as not here, it said %s",
                    y,
                    said);
        }
        helper.succeed();
    }

    // ---- LayerBlocks.groundOf, through RoomSeal ----

    /** The shell of a room is sealed with the rock of the band it is cut in, and with plain stone in the surface band. */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aSealFillsWithTheRockOfTheBandItIsIn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 23320;
        BlockPos[] centres = {
            new BlockPos(x, ONE.minY() + 80, Z),
            new BlockPos(x + 32, TWO.minY() + 120, Z),
            new BlockPos(x + 64, CampaignBands.SURFACE.minY() + 150, Z)
        };
        Block[] expected = {LayerBlocks.layerGround(1), LayerBlocks.layerGround(2), Blocks.STONE};
        int[] sealed = {0};
        FarChunks.awaitEntityTicking(helper, level, List.of(centres), index -> {
            BlockPos centre = centres[index];
            RoomCarver.carve(
                    level, centre.offset(-4, -4, -4), centre.offset(4, 4, 4), Blocks.DEEPSLATE.defaultBlockState());
            BlockPos shell = centre.offset(-2, 0, 0);
            // The lava a worldgen cave would leave in the shell, put there after the room was carved.
            level.setBlock(shell, Blocks.LAVA.defaultBlockState(), 0);
            RoomSeal.seal(level, centre.offset(-1, -1, -1), centre.offset(1, 1, 1));
            expect(
                    helper,
                    level.getBlockState(shell).is(expected[index]),
                    "the shell at y=%d was sealed with %s, expected %s",
                    centre.getY(),
                    level.getBlockState(shell).getBlock(),
                    expected[index]);
            if (++sealed[0] == centres.length) {
                helper.succeed();
            }
        });
    }

    // ---- HandbookTriggers.deepEnough ----

    /** A bore below the pad's ground by the tuned depth counts in the surface band; any Y in a layer band counts, and a bore near the ground does not. */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aPodInALayerBandCountsAsDeepEnough(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ColonySite.Placed colony = Colony.placed(server).orElseThrow(() -> failure(helper, "the colony was not built"));
        MockPlayer mock = MockPlayers.join(helper, "band-deep");
        if (Charters.found(
                        server,
                        mock.player().getUUID(),
                        "Band Deep " + UUID.randomUUID().toString().substring(0, 8))
                .isPresent()) {
            throw failure(helper, "founding the charter should succeed");
        }
        int x = 23384;
        BlockPos spot = new BlockPos(x, ONE.minY() + 100, Z);
        mock.teleportTo(level, Vec3.atBottomCenterOf(spot), 0f, 0f);
        FarChunks.awaitEntityTicking(helper, level, spot, () -> {
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(Vec3.atBottomCenterOf(spot));
            level.addFreshEntity(pod);
            if (!mock.player().startRiding(pod, true, false)) {
                throw failure(helper, "the player should board the pod");
            }
            pod.setDrilling(true);
            pod.setDrillDirection(Direction.DOWN);
            String drillDown = HandbookChaptersOneToFiveTest.directive("fuel_is_life", "drill_down");
            pod.setPos(x, colony.groundY() - HandbookTuning.DEFAULT.drillDownBlocks() + 1.0, Z);
            tickHandbook(pod);
            expect(
                    helper,
                    !completed(server, mock, drillDown),
                    "a bore 9 blocks under the pad, in the surface band, counted as deep enough");
            pod.setPos(Vec3.atBottomCenterOf(spot));
            tickHandbook(pod);
            expect(helper, completed(server, mock, drillDown), "a bore in layer 1's band did not count as deep enough");
            pod.discard();
            helper.succeed();
        });
    }

    private static boolean completed(MinecraftServer server, MockPlayer mock, String directive) {
        return HandbookProgress.completedFor(server, mock.player().getUUID()).stream()
                .anyMatch(id -> id.toString().equals(directive));
    }

    private static void tickHandbook(PodEntity pod) {
        pod.tickCount = 0;
        PodEvents.AFTER_TICK.invoker().afterTick(pod);
    }

    // ---- WorkOrders.offered and CockpitReading.deepestBlocks, from the band table ----

    /** The depths the work orders open at and the cockpit's depth gauge ends at are the ones the layer dimensions gave. */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void theBandTableGivesTheDepthsTheLayerDimensionsGave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        expect(
                helper,
                LayerChain.layerCount(level) == 3,
                "the campaign level holds %d layers",
                LayerChain.layerCount(level));
        int[] tops = {127, 319, 575, 831};
        for (int layer = 1; layer <= tops.length; layer++) {
            expect(
                    helper,
                    LayerChain.topDepth(level, layer) == tops[layer - 1],
                    "layer %d opens at depth %d, expected %d",
                    layer,
                    LayerChain.topDepth(level, layer),
                    tops[layer - 1]);
        }
        expect(
                helper,
                LayerChain.deepestDepth(level) == 831,
                "the deepest depth is %d",
                LayerChain.deepestDepth(level));
        expect(
                helper,
                Depth.of(level, ONE.maxY()) == 128
                        && Depth.of(level, TWO.minY()) == 575
                        && Depth.of(level, LAST.minY()) == 831,
                "the first block of layer 1 is at depth %d, the floor of layer 2 at %d and the floor of layer 3 at %d",
                Depth.of(level, ONE.maxY()),
                Depth.of(level, TWO.minY()),
                Depth.of(level, LAST.minY()));
        helper.succeed();
    }

    /**
     * The Morale Initiative opens at the top of layer 3, depth 575, as the band table gives it for the level the player is in: a charter at
     * 574 is not offered it and one at 575 is. The cockpit's depth gauge is the pod's depth out of the deepest block, 831 (layer 3's floor).
     */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void workOrdersOpenAndTheGaugeEndsAtTheDepthsOfTheBandTable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        MockPlayer mock = MockPlayers.join(helper, "band-orders");
        if (Charters.found(
                        server,
                        mock.player().getUUID(),
                        "Band Orders " + UUID.randomUUID().toString().substring(0, 8))
                .isPresent()) {
            throw failure(helper, "founding the charter should succeed");
        }
        CharterId charter = Charters.charterOfOrThrow(server, mock.player().getUUID())
                .orElseThrow()
                .id();
        BlockPos processor = new BlockPos(23448, ONE.minY() + 100, Z);
        mock.teleportTo(level, Vec3.atBottomCenterOf(processor), 0f, 0f);
        for (int depth : new int[] {574, 575}) {
            Charters.recordDeepestPoint(server, charter, depth);
            boolean offered = WorkOrders.view(
                            server,
                            mock.player(),
                            Charters.charterOfOrThrow(server, mock.player().getUUID()),
                            processor)
                    .orders()
                    .stream()
                    .anyMatch(entry -> entry.order() == WorkOrder.MORALE_INITIATIVE);
            expect(
                    helper,
                    offered == (depth == 575),
                    "a charter at depth %d is %s the Morale Initiative",
                    depth,
                    offered ? "offered" : "not offered");
        }
        PodEntity pod = pod(level, LAST.minY(), 10f);
        expect(
                helper,
                Math.abs(CockpitReading.of(pod, 0).depth() - 1.0) < 1e-9,
                "the gauge at the last layer's floor reads %s",
                CockpitReading.of(pod, 0).depth());
        pod.setPos(23128, TWO.minY(), Z);
        expect(
                helper,
                Math.abs(CockpitReading.of(pod, 0).depth() - 575.0 / 831.0) < 1e-9,
                "the gauge at layer 2's floor reads %s",
                CockpitReading.of(pod, 0).depth());
        pod.setPos(23128, ONE.maxY(), Z);
        expect(
                helper,
                Math.abs(CockpitReading.of(pod, 0).depth() - 128.0 / 831.0) < 1e-9,
                "the gauge at layer 1's top reads %s",
                CockpitReading.of(pod, 0).depth());
        helper.succeed();
    }

    // ---- Zones ----

    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void zonesFollowTheBandAtAY(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        expect(
                helper,
                Zones.of(level, ONE.maxY()).map(Zones.Zone::layer).equals(Optional.of(1)),
                "the top of layer 1 is in zone %s",
                Zones.of(level, ONE.maxY()));
        expect(
                helper,
                Zones.of(level, TWO.minY()).map(Zones.Zone::layer).equals(Optional.of(2)),
                "the floor of layer 2 is in zone %s",
                Zones.of(level, TWO.minY()));
        expect(helper, Zones.of(level, CampaignBands.SURFACE.minY() + 100).isEmpty(), "the surface has a zone");
        expect(helper, Zones.of(level, CampaignBands.UNBUILT.minY() + 100).isEmpty(), "unbuilt depth has a zone");
        helper.succeed();
    }

    // ---- LayerChain ----

    /** The overworld, whatever its dimension type, is one surface band the height of its level, and no layer. */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void anOverworldThatIsNotTheCampaignIsOneSurfaceBand(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        expect(helper, !LayerChain.isCampaign(overworld), "the test overworld is a campaign level");
        LayerChain.Band whole = TestLayers.band(overworld, LayerChain.SURFACE);
        for (int y : new int[] {whole.minY(), 64, whole.minY() + whole.height() - 1}) {
            LayerChain.Band band =
                    LayerChain.bandAt(overworld, y).orElseThrow(() -> failure(helper, "no band in the overworld"));
            expect(helper, band.equals(whole), "the overworld at y=%d is %s, expected %s", y, band, whole);
        }
        expect(helper, LayerChain.layerAt(overworld, 64).isEmpty(), "the overworld surface is a layer");
        helper.succeed();
    }

    // ---- LayerCommands.goTo ----

    private static ServerLevel op(GameTestHelper helper, MockPlayer mock, String command)
            throws CommandSyntaxException {
        MinecraftServer server = helper.getLevel().getServer();
        var source = mock.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
        server.getCommands().getDispatcher().execute(command, source);
        return mock.player().level();
    }

    /** A gas pocket is solid, so goto never puts the player inside one: it passes over the pocket in the only room of the column and ends in the pocket it cuts. */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void gotoNeverStandsAPlayerInGas(GameTestHelper helper) throws CommandSyntaxException {
        int x = 23512;
        ServerLevel level = helper.getLevel();
        int floor = ONE.maxY() - 6;
        BlockPos feet = new BlockPos(x, floor + 1, Z);
        MockPlayer mock = MockPlayers.join(helper, "band-goto-gas");
        mock.player().setPos(x + 0.5, CampaignBands.SURFACE.minY() + 100, Z + 0.5);
        FarChunks.awaitEntityTicking(helper, level, feet, () -> {
            try {
                // A bed of stone with the only room in the column at the floor's top, and gas in the room's lower cell.
                RoomCarver.carve(level, feet.offset(0, -1, 0), feet.offset(0, 1, 0), Blocks.STONE.defaultBlockState());
                RoomCarver.carve(level, feet.above(), feet.above(), Blocks.AIR.defaultBlockState());
                RoomCarver.carve(level, feet, feet, HazardBlocks.GAS_POCKET.defaultBlockState());
                RoomCarver.carve(
                        level, feet.above(2), feet.above(ONE.maxY() - feet.getY()), Blocks.STONE.defaultBlockState());
                op(helper, mock, "deepcharter layer goto 1");
                BlockPos at = mock.player().blockPosition();
                expect(
                        helper,
                        !level.getBlockState(at).is(HazardBlocks.GAS_POCKET)
                                && !level.getBlockState(at.above()).is(HazardBlocks.GAS_POCKET),
                        "goto stood the player in gas at %s",
                        at.toShortString());
                expect(
                        helper,
                        at.getY() >= ONE.minY() + CampaignBands.crustRows() && at.getY() <= ONE.maxY(),
                        "goto put the player at y=%d, outside layer 1",
                        at.getY());
            } catch (CommandSyntaxException e) {
                throw failure(helper, "goto failed: %s", e.getMessage());
            } finally {
                mock.leave();
            }
            helper.succeed();
        });
    }

    /** In the campaign world goto stays in the level and lands in the layer's band, on a floor, with room to stand. */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void gotoLandsInTheBandOfTheLayer(GameTestHelper helper) throws CommandSyntaxException {
        MockPlayer mock = MockPlayers.join(helper, "band-goto");
        ServerLevel level = helper.getLevel();
        try {
            // A column that is not loaded: goto must generate it and not read an empty heightmap.
            mock.player().setPos(200_000.5, CampaignBands.SURFACE.minY() + 100, 200_000.5);
            for (Band band : CampaignBands.LAYERS) {
                ServerLevel after =
                        op(helper, mock, "deepcharter layer goto " + (CampaignBands.LAYERS.indexOf(band) + 1));
                BlockPos feet = mock.player().blockPosition();
                expect(helper, after == level, "goto left the campaign level for %s", after.dimension());
                expect(
                        helper,
                        feet.getY() >= band.minY() + CampaignBands.crustRows() && feet.getY() <= band.maxY() - 1,
                        "goto put the player at y=%d, outside %s (%d to %d)",
                        feet.getY(),
                        band.name(),
                        band.minY(),
                        band.maxY());
                expect(
                        helper,
                        level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                                && level.getBlockState(feet.above())
                                        .getCollisionShape(level, feet.above())
                                        .isEmpty()
                                && !level.getBlockState(feet.below())
                                        .getCollisionShape(level, feet.below())
                                        .isEmpty(),
                        "the player at %s has no floor and room to stand",
                        feet.toShortString());
            }
            try {
                op(helper, mock, "deepcharter layer goto " + (CampaignBands.lastLayer() + 1));
                throw failure(
                        helper,
                        "goto %d should fail: only %d layers exist",
                        CampaignBands.lastLayer() + 1,
                        CampaignBands.lastLayer());
            } catch (CommandSyntaxException outOfRange) {
                expect(
                        helper,
                        String.valueOf(outOfRange.getMessage()).contains("No layer " + (CampaignBands.lastLayer() + 1)),
                        "expected the no-such-layer failure, got %s",
                        outOfRange.getMessage());
            }
        } finally {
            mock.leave();
        }
        helper.succeed();
    }
}
