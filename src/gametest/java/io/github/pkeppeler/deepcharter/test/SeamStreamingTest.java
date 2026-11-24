package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerRegistry;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.SeamStreaming;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.mixin.ServerChunkCacheAccess;
import io.github.pkeppeler.deepcharter.test.support.BlockingLoads;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.SecondSeat;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests of the arrival area (#1159, ADR 0089) on the shipped layer_1 to layer_2 crossing: the server loads the area under a
 * vehicle that nears a floor, ahead of the crossing and off the server thread; no ticket is taken above the lead or in the last level;
 * the ticket is let go after a crossing, a retreat or a disconnect; a pod with two riders is one approach; the centre follows the root.
 *
 * <p>Nothing here forces or loads an arrival chunk: the test only reads what the mod's ticket made. The tickets are read from vanilla's
 * {@code TicketStorage} ({@link ServerChunkCacheAccess}), not from {@link SeamStreaming}'s own record of its approaches. Each test has its
 * own X, a thousand blocks apart, so no two arrival areas touch.
 */
public class SeamStreamingTest {
    /** Chunks in the arrival area: a square of {@code 2 * radius + 1} on a side. */
    private static final int AREA_CHUNKS =
            (2 * LayerTuning.DEFAULT.seamRadius() + 1) * (2 * LayerTuning.DEFAULT.seamRadius() + 1);
    /** Blocks above the floor of layer 1 where a test holds its vehicle: inside the floor margin, so it always streams. */
    private static final int HOLD_HEIGHT = 100;
    /** Blocks above the floor where a falling vehicle starts, a drop of a few ticks. */
    private static final int DROP_HEIGHT = 8;
    /** Half the width of the air shaft cut above the floor: wide enough for a pod. */
    private static final int SHAFT_HALF_WIDTH = 2;
    /** Ticks a negative case watches for a ticket that must not come. */
    private static final int WATCH_TICKS = 40;
    /** Ticks past the ticket's timeout that a release may take. */
    private static final int RELEASE_SLACK_TICKS = 20;
    /** Ticks an approach may outlive the vehicle that left its zone: the next tick of the level it left. */
    private static final int APPROACH_SLACK_TICKS = 5;
    /** The test's own ticks beside its chunk wait: a drop, a crossing, and a release of the ticket's 100-tick timeout. */
    private static final int WORK_TICKS = 400;

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + WORK_TICKS;
    /** Blocks between the X of two tests, so their arrival areas stay apart. */
    private static final int SITE_SPACING = 1000;
    /** A chunk's middle: a shaft there stays inside one chunk. */
    private static final double CHUNK_MIDDLE = 8.5;

    private static final double FIRST_SITE = 40_000 + CHUNK_MIDDLE;

    /** Where a test stands: a column of layer 1, and the layer under it. */
    private record Site(ServerLevel one, ServerLevel two, double x, double z) {
        static Site of(GameTestHelper helper, int index) {
            MinecraftServer server = helper.getLevel().getServer();
            return new Site(
                    TestLayers.layerDimension(server, 1),
                    TestLayers.layerDimension(server, 2),
                    FIRST_SITE + (double) SITE_SPACING * index,
                    FIRST_SITE);
        }

        int floorY() {
            return TestLayers.dimensionBand(one).minY();
        }

        Vec3 at(int heightAboveFloor) {
            return new Vec3(x, floorY() + heightAboveFloor, z);
        }

        ChunkPos centre() {
            return ChunkPos.containing(BlockPos.containing(x, 0, z));
        }

        /** An air shaft from the floor of layer 1 up to {@code topHeight}, so a mock or a pod in it is not in rock and can fall out. */
        void carveShaft(double shaftX, int topHeight) {
            BlockPos column = BlockPos.containing(shaftX, floorY(), z);
            RoomCarver.carve(
                    one,
                    column.offset(-SHAFT_HALF_WIDTH, 0, -SHAFT_HALF_WIDTH),
                    column.offset(SHAFT_HALF_WIDTH, topHeight, SHAFT_HALF_WIDTH),
                    Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_ALL);
        }
    }

    /** One test's site, and the wall-clock limit of its wait for the arrival area. */
    private record Run(GameTestHelper helper, Site site, FarChunks.Deadline deadline) {
        static Run of(GameTestHelper helper, int index) {
            return new Run(helper, Site.of(helper, index), FarChunks.deadline());
        }

        /** Whether the whole arrival area is loaded under a ticket; otherwise sleeps a little and, past the limit, fails naming what is missing. */
        boolean arrivalReady() {
            int loaded = loadedAround(site.two(), site.centre());
            boolean ready = loaded == AREA_CHUNKS
                    && !arrivalTickets(site.two(), site.centre()).isEmpty();
            deadline.await(
                    helper,
                    site.two(),
                    ready,
                    () -> "the arrival area of " + site.centre() + " has " + loaded + " of " + AREA_CHUNKS
                            + " chunks loaded and tickets " + arrivalTickets(site.two(), site.centre()));
            return ready;
        }

        /**
         * Waits until every position of {@code ticking} in layer 1 ticks entities, then runs {@code setup} once and, from the next tick,
         * {@code step} with the ticks since. {@code step} ends the test itself.
         */
        void stage(List<BlockPos> ticking, Runnable setup, IntConsumer step) {
            boolean[] ready = {false};
            long[] placedAt = {-1};
            FarChunks.awaitAllEntityTicking(helper, site.one(), ticking, () -> ready[0] = true);
            TestTicks.each(helper, () -> {
                if (placedAt[0] < 0) {
                    if (ready[0]) {
                        setup.run();
                        placedAt[0] = helper.getTick();
                    }
                    return;
                }
                step.accept((int) (helper.getTick() - placedAt[0]));
            });
        }

        void stage(Runnable setup, IntConsumer step) {
            stage(List.of(BlockPos.containing(site.at(HOLD_HEIGHT))), setup, step);
        }
    }

    /** How many chunks of the arrival area around {@code centre} are loaded in {@code level}. */
    private static int loadedAround(ServerLevel level, ChunkPos centre) {
        int radius = LayerTuning.DEFAULT.seamRadius();
        int loaded = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (level.getChunkSource().getChunkNow(centre.x() + dx, centre.z() + dz) != null) {
                    loaded++;
                }
            }
        }
        return loaded;
    }

    /** The arrival tickets vanilla holds on {@code chunk} of {@code level}, read from its ticket storage. */
    private static List<Ticket> arrivalTickets(ServerLevel level, ChunkPos chunk) {
        return ((ServerChunkCacheAccess) level.getChunkSource())
                .deepcharter$ticketStorage().getTickets(chunk.pack()).stream()
                        .filter(ticket -> ticket.getType() == LayerRegistry.SEAM_ARRIVAL)
                        .toList();
    }

    /** Whether any level of the server holds an arrival ticket on {@code chunk}. */
    private static boolean anyArrivalTicket(MinecraftServer server, ChunkPos chunk) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!arrivalTickets(level, chunk).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Lowers a vehicle that is still in layer 1 by a block, a mock's fall: it has no client gravity. */
    private static void fall(Site site, Entity vehicle) {
        if (vehicle.level() == site.one()) {
            vehicle.setPos(vehicle.getX(), vehicle.getY() - 1, vehicle.getZ());
        }
    }

    /** A vehicle at {@code height} above layer 1's floor holds there: zero speed, so a pod does not fall of its own weight. */
    private static void hold(Site site, PodEntity pod, int height) {
        pod.setPos(site.at(height));
        pod.setDeltaMovement(Vec3.ZERO);
    }

    /** A mock held 100 blocks above layer 1's floor, inside the floor margin, gets the whole arrival area before it moves. */
    @GameTest(maxTicks = MAX_TICKS)
    public void theArrivalAreaIsLoadedAndTicketedBeforeAnyCrossing(GameTestHelper helper) {
        Run run = Run.of(helper, 0);
        MockPlayer mock = MockPlayers.join(helper, "seam-ahead");
        expect(
                helper,
                HOLD_HEIGHT <= LayerTuning.DEFAULT.seamFloorMargin(),
                "the hold height %d is outside the floor margin %d",
                HOLD_HEIGHT,
                LayerTuning.DEFAULT.seamFloorMargin());
        run.stage(
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 2);
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    if (!run.arrivalReady()) {
                        return;
                    }
                    List<Ticket> tickets =
                            arrivalTickets(run.site().two(), run.site().centre());
                    expect(helper, tickets.size() == 1, "expected one arrival ticket on the centre, got %s", tickets);
                    expect(
                            helper,
                            mock.player().level() == run.site().one(),
                            "the mock left layer 1 before the area was ready");
                    helper.succeed();
                });
    }

    /** Above the lead and the floor margin, with no descent, nothing is taken: 16 blocks past the margin streams no area. */
    @GameTest(maxTicks = MAX_TICKS)
    public void noTicketIsTakenAboveTheFloorMarginWithoutADescent(GameTestHelper helper) {
        Run run = Run.of(helper, 1);
        int outside = LayerTuning.DEFAULT.seamFloorMargin() + 16;
        expect(
                helper,
                TestLayers.dimensionBand(run.site().one()).height() > outside + 2,
                "layer 1 is %d tall, too short to hold a mock %d above its floor",
                TestLayers.dimensionBand(run.site().one()).height(),
                outside);
        MockPlayer mock = MockPlayers.join(helper, "seam-far");
        run.stage(
                List.of(BlockPos.containing(run.site().at(outside))),
                () -> {
                    run.site().carveShaft(run.site().x(), outside + 2);
                    mock.teleportTo(run.site().one(), run.site().at(outside), 0, 0);
                },
                ticks -> {
                    expect(
                            helper,
                            arrivalTickets(run.site().two(), run.site().centre())
                                            .isEmpty()
                                    && SeamStreaming.approachOf(mock.player().getUUID())
                                            .isEmpty(),
                            "an arrival was streamed %d ticks into a hold %d blocks above the floor",
                            ticks,
                            outside);
                    if (ticks >= WATCH_TICKS) {
                        helper.succeed();
                    }
                });
    }

    /** The last level has no level below it, and the campaign world has no seam in it: a mock near either floor takes no arrival ticket. */
    @GameTest(maxTicks = MAX_TICKS)
    public void noTicketIsTakenInTheLastLevelOrTheCampaignWorld(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        int layers = LayerChain.count(server.registryAccess());
        ServerLevel last = TestLayers.layerDimension(server, layers);
        ServerLevel campaign = TestLayers.level(server, 1);
        Vec3 inLast = new Vec3(
                FIRST_SITE + 2.0 * SITE_SPACING, TestLayers.dimensionBand(last).minY() + 50, FIRST_SITE);
        Vec3 inCampaign = new Vec3(FIRST_SITE + 3.0 * SITE_SPACING, TestLayers.worldY(1, 20.0), FIRST_SITE);
        MockPlayer inLastMock = MockPlayers.join(helper, "seam-last");
        MockPlayer inCampaignMock = MockPlayers.join(helper, "seam-campaign");
        boolean[] lastTicking = {false};
        boolean[] campaignTicking = {false};
        FarChunks.awaitEntityTicking(helper, last, BlockPos.containing(inLast), () -> lastTicking[0] = true);
        FarChunks.awaitEntityTicking(
                helper, campaign, BlockPos.containing(inCampaign), () -> campaignTicking[0] = true);
        long[] placedAt = {-1};
        TestTicks.each(helper, () -> {
            if (placedAt[0] < 0) {
                if (lastTicking[0] && campaignTicking[0]) {
                    inLastMock.teleportTo(last, inLast, 0, 0);
                    inCampaignMock.teleportTo(campaign, inCampaign, 0, 0);
                    placedAt[0] = helper.getTick();
                }
                return;
            }
            expect(
                    helper,
                    !anyArrivalTicket(server, ChunkPos.containing(BlockPos.containing(inLast)))
                            && !anyArrivalTicket(server, ChunkPos.containing(BlockPos.containing(inCampaign)))
                            && SeamStreaming.approachOf(inLastMock.player().getUUID())
                                    .isEmpty()
                            && SeamStreaming.approachOf(inCampaignMock.player().getUUID())
                                    .isEmpty(),
                    "an arrival was streamed for a mock in the last level or the campaign world");
            if (helper.getTick() - placedAt[0] >= WATCH_TICKS) {
                helper.succeed();
            }
        });
    }

    /**
     * A mock that falls out of layer 1 once its arrival area is ready crosses without the server thread waiting for a single chunk of
     * the area. The loads are read by a mixin on {@code ServerChunkCache.getChunk}, armed after the wait.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aCrossingWithTheAreaStreamedNeverBlocksTheServerThreadOnIt(GameTestHelper helper) {
        Run run = Run.of(helper, 4);
        MockPlayer mock = MockPlayers.join(helper, "seam-no-block");
        boolean[] armed = {false};
        int[] crossedAt = {-1};
        run.stage(
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 2);
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    ServerPlayer player = mock.player();
                    if (!armed[0]) {
                        if (run.arrivalReady()) {
                            BlockingLoads.arm(helper.getBounds());
                            armed[0] = true;
                        }
                        return;
                    }
                    if (crossedAt[0] < 0) {
                        if (TestLayers.isInDimensionOf(player, 2)) {
                            crossedAt[0] = ticks;
                        } else {
                            fall(run.site(), player);
                        }
                        return;
                    }
                    // A few ticks in layer 2, so what the arrival does after the teleport is in the record too.
                    if (ticks - crossedAt[0] < 10 || BlockingLoads.disarmed()) {
                        return;
                    }
                    List<BlockingLoads.Load> loads = BlockingLoads.disarm();
                    List<BlockingLoads.Load> inArea = loads.stream()
                            .filter(load -> load.level().equals(run.site().two().dimension()))
                            .filter(load -> inArea(run.site(), load))
                            .toList();
                    expect(
                            helper,
                            inArea.isEmpty(),
                            "the server thread waited for %d chunks of the streamed arrival area: %s",
                            inArea.size(),
                            inArea);
                    helper.succeed();
                });
    }

    /**
     * The control of the case above: a mock that is under the floor the first tick it is near it has no lead, so its crossing does wait for
     * the chunks, and the crossing logs how many of the area were loaded. It proves that the mixin sees a blocking load.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aCrossingWithNoLeadWaitsForTheAreaAndSaysSo(GameTestHelper helper) {
        Run run = Run.of(helper, 5);
        MockPlayer mock = MockPlayers.join(helper, "seam-no-lead");
        LogCapture log = LogCapture.start(mock.player().getUUID().toString());
        run.stage(
                List.of(BlockPos.containing(run.site().at(DROP_HEIGHT))),
                () -> {
                    run.site().carveShaft(run.site().x(), DROP_HEIGHT + 2);
                    BlockingLoads.arm(helper.getBounds());
                    mock.teleportTo(run.site().one(), run.site().at(DROP_HEIGHT), 0, 0);
                    mock.player()
                            .setPos(
                                    run.site().x(),
                                    run.site().floorY() - 1,
                                    run.site().z());
                },
                ticks -> {
                    if (!TestLayers.isInDimensionOf(mock.player(), 2) || BlockingLoads.disarmed()) {
                        return;
                    }
                    List<BlockingLoads.Load> loads = BlockingLoads.disarm();
                    List<BlockingLoads.Load> inArea = loads.stream()
                            .filter(load -> load.level().equals(run.site().two().dimension()))
                            .filter(load -> inArea(run.site(), load))
                            .toList();
                    expect(
                            helper,
                            !inArea.isEmpty(),
                            "a crossing with no lead waited for no chunk of the area; every wait it saw: %s",
                            loads);
                    expect(
                            helper,
                            log.warnings().size() == 1,
                            "expected one warning that the arrival was not ready, got %s",
                            log.warnings());
                    helper.succeed();
                });
    }

    private static boolean inArea(Site site, BlockingLoads.Load load) {
        int radius = LayerTuning.DEFAULT.seamRadius();
        return Math.abs(load.chunkX() - site.centre().x()) <= radius
                && Math.abs(load.chunkZ() - site.centre().z()) <= radius;
    }

    /** The ticket and the approach are let go within the ticket's timeout of the crossing that ended the approach. */
    @GameTest(maxTicks = MAX_TICKS)
    public void theTicketIsReleasedAfterACrossing(GameTestHelper helper) {
        Run run = Run.of(helper, 6);
        MockPlayer mock = MockPlayers.join(helper, "seam-release-cross");
        UUID root = mock.player().getUUID();
        Release release = new Release(run, root);
        run.stage(
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 2);
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    if (!release.armed()) {
                        if (run.arrivalReady()) {
                            release.arm();
                        }
                        return;
                    }
                    if (!TestLayers.isInDimensionOf(mock.player(), 2)) {
                        fall(run.site(), mock.player());
                        return;
                    }
                    release.watch(ticks);
                });
    }

    /** A vehicle that climbs back above the margin, with no descent, lets go of its ticket and its approach. */
    @GameTest(maxTicks = MAX_TICKS)
    public void theTicketIsReleasedAfterARetreat(GameTestHelper helper) {
        Run run = Run.of(helper, 7);
        int outside = LayerTuning.DEFAULT.seamFloorMargin() + 16;
        MockPlayer mock = MockPlayers.join(helper, "seam-release-retreat");
        Release release = new Release(run, mock.player().getUUID());
        run.stage(
                List.of(
                        BlockPos.containing(run.site().at(HOLD_HEIGHT)),
                        BlockPos.containing(run.site().at(outside))),
                () -> {
                    run.site().carveShaft(run.site().x(), outside + 2);
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    if (!release.armed()) {
                        if (run.arrivalReady()) {
                            release.arm();
                            mock.player().setPos(run.site().at(outside));
                        }
                        return;
                    }
                    release.watch(ticks);
                });
    }

    /** A player who disconnects mid-approach takes the approach with them, and the ticket lapses. */
    @GameTest(maxTicks = MAX_TICKS)
    public void theTicketIsReleasedAfterADisconnect(GameTestHelper helper) {
        Run run = Run.of(helper, 8);
        MockPlayer mock = MockPlayers.join(helper, "seam-release-leave");
        Release release = new Release(run, mock.player().getUUID());
        run.stage(
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 2);
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    if (!release.armed()) {
                        if (run.arrivalReady()) {
                            release.arm();
                            mock.leave();
                        }
                        return;
                    }
                    release.watch(ticks);
                });
    }

    /**
     * What a release test watches once its trigger has happened: the approach is gone within a few ticks and the ticket within its
     * timeout and a slack. The ticks are counted from the first {@link #watch}.
     */
    private static final class Release {
        private final Run run;
        private final UUID root;
        private boolean armed;
        private int firstWatch = -1;

        Release(Run run, UUID root) {
            this.run = run;
            this.root = root;
        }

        boolean armed() {
            return armed;
        }

        /** The arrival was ready and the approach is on: nothing is let go yet. */
        void arm() {
            if (!armed) {
                expect(
                        run.helper(),
                        SeamStreaming.approachOf(root).isPresent(),
                        "no approach under way before the release");
                armed = true;
            }
        }

        void watch(int ticks) {
            if (firstWatch < 0) {
                firstWatch = ticks;
            }
            int waited = ticks - firstWatch;
            boolean approachGone = SeamStreaming.approachOf(root).isEmpty();
            boolean ticketGone =
                    arrivalTickets(run.site().two(), run.site().centre()).isEmpty();
            if (waited > APPROACH_SLACK_TICKS) {
                expect(run.helper(), approachGone, "the approach is still there %d ticks after the release", waited);
            }
            if (approachGone && ticketGone) {
                run.helper().succeed();
                return;
            }
            if (waited > LayerTuning.DEFAULT.seamTicketTimeout() + RELEASE_SLACK_TICKS) {
                throw failure(
                        run.helper(),
                        "%d ticks after the release the ticket is still held: %s",
                        waited,
                        arrivalTickets(run.site().two(), run.site().centre()));
            }
        }
    }

    /** A pod carrying two players is one approach, keyed by the pod and listing both; once both cross it is gone. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aPodWithTwoRidersIsOneApproach(GameTestHelper helper) {
        Run run = Run.of(helper, 9);
        MockPlayer pilot = MockPlayers.join(helper, "seam-pilot");
        MockPlayer rider = MockPlayers.join(helper, "seam-rider");
        PodEntity[] pods = new PodEntity[1];
        boolean[] dropping = {false};
        run.stage(
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 4);
                    PodEntity pod =
                            TestEntities.create(PodRegistry.POD, run.site().one());
                    SecondSeat.add(pod);
                    hold(run.site(), pod, HOLD_HEIGHT);
                    run.site().one().addFreshEntity(pod);
                    pods[0] = pod;
                    pilot.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                    rider.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                    expect(
                            helper,
                            pilot.player().startRiding(pod, true, false)
                                    && rider.player().startRiding(pod, true, false),
                            "the mocks could not board the pod");
                },
                ticks -> {
                    PodEntity pod = pods[0];
                    if (!dropping[0]) {
                        hold(run.site(), pod, HOLD_HEIGHT);
                        if (!run.arrivalReady()) {
                            return;
                        }
                        var approach = SeamStreaming.approachOf(pod.getUUID());
                        expect(helper, approach.isPresent(), "no approach keyed by the pod");
                        expect(
                                helper,
                                Set.copyOf(approach.get().riders())
                                        .equals(Set.of(
                                                pilot.player().getUUID(),
                                                rider.player().getUUID())),
                                "the approach lists %s, not both riders",
                                approach.get().riders());
                        expect(
                                helper,
                                SeamStreaming.approachOf(pilot.player().getUUID())
                                                .isEmpty()
                                        && SeamStreaming.approachOf(
                                                        rider.player().getUUID())
                                                .isEmpty(),
                                "a rider has an approach of its own");
                        expect(
                                helper,
                                arrivalTickets(run.site().two(), run.site().centre())
                                                .size()
                                        == 1,
                                "expected one ticket for the pod");
                        dropping[0] = true;
                        return;
                    }
                    if (TestLayers.isInDimensionOf(pilot.player(), 2)
                            && TestLayers.isInDimensionOf(rider.player(), 2)) {
                        expect(
                                helper,
                                SeamStreaming.approachOf(pod.getUUID()).isEmpty(),
                                "the pod's approach outlived the crossing");
                        pilot.player().stopRiding();
                        rider.player().stopRiding();
                        helper.succeed();
                        return;
                    }
                    if (!pod.isRemoved()) {
                        fall(run.site(), pod);
                        pod.setDeltaMovement(Vec3.ZERO);
                    }
                });
    }

    /** The arrival area follows its vehicle: two chunks to the side, the ticket and the loaded area are on the new centre. */
    @GameTest(maxTicks = MAX_TICKS)
    public void theCentreFollowsTheRootTwoChunksSideways(GameTestHelper helper) {
        Run run = Run.of(helper, 10);
        double movedX = run.site().x() + 32;
        ChunkPos moved =
                ChunkPos.containing(BlockPos.containing(movedX, 0, run.site().z()));
        MockPlayer mock = MockPlayers.join(helper, "seam-sideways");
        boolean[] sideways = {false};
        run.stage(
                List.of(
                        BlockPos.containing(run.site().at(HOLD_HEIGHT)),
                        BlockPos.containing(
                                movedX,
                                run.site().floorY() + HOLD_HEIGHT,
                                run.site().z())),
                () -> {
                    run.site().carveShaft(run.site().x(), HOLD_HEIGHT + 2);
                    run.site().carveShaft(movedX, HOLD_HEIGHT + 2);
                    mock.teleportTo(run.site().one(), run.site().at(HOLD_HEIGHT), 0, 0);
                },
                ticks -> {
                    if (!sideways[0]) {
                        if (run.arrivalReady()) {
                            mock.player()
                                    .setPos(
                                            movedX,
                                            run.site().floorY() + HOLD_HEIGHT,
                                            run.site().z());
                            sideways[0] = true;
                        }
                        return;
                    }
                    int loaded = loadedAround(run.site().two(), moved);
                    boolean ticketed = !arrivalTickets(run.site().two(), moved).isEmpty();
                    boolean followed = SeamStreaming.approachOf(mock.player().getUUID())
                            .filter(approach -> approach.centre().equals(moved))
                            .isPresent();
                    run.deadline()
                            .await(
                                    helper,
                                    run.site().two(),
                                    loaded == AREA_CHUNKS && ticketed && followed,
                                    () -> "the area did not follow to " + moved + ": " + loaded + " of " + AREA_CHUNKS
                                            + " chunks, ticketed " + ticketed + ", approach follows " + followed);
                    if (loaded == AREA_CHUNKS && ticketed && followed) {
                        helper.succeed();
                    }
                });
    }

    /**
     * The trigger rule, pure: the lead is 0.32 blocks a row of the world below, so 656 blocks for 2048 rows and 1301 for 4064; a vehicle
     * inside the floor margin streams at any speed, and beyond it only if its speed would cover the distance to the floor in the lead
     * time, which is the full lead at 78 blocks a second.
     */
    @GameTest
    public void theLeadScalesWithTheHeightBelowAndTheSpeed(GameTestHelper helper) {
        LayerTuning tuning = LayerTuning.DEFAULT;
        expect(helper, SeamStreaming.lead(2048, tuning) == 656, "lead of 2048: %d", SeamStreaming.lead(2048, tuning));
        expect(helper, SeamStreaming.lead(4064, tuning) == 1301, "lead of 4064: %d", SeamStreaming.lead(4064, tuning));
        int margin = tuning.seamFloorMargin();
        int lead = SeamStreaming.lead(2048, tuning);
        double terminal = tuning.seamDesignFallSpeed();
        int half = lead / 2;
        expect(helper, SeamStreaming.shouldStream(margin, 0, 2048, tuning), "a stopped vehicle at the margin streams");
        expect(
                helper,
                !SeamStreaming.shouldStream(margin + 1, 0, 2048, tuning),
                "a stopped vehicle past the margin does not stream");
        expect(
                helper,
                !SeamStreaming.shouldStream(lead, 0, 2048, tuning),
                "a stopped vehicle at the lead does not stream");
        expect(
                helper,
                SeamStreaming.shouldStream(lead, terminal, 2048, tuning),
                "a vehicle at 78 blocks a second streams at the lead");
        expect(
                helper,
                !SeamStreaming.shouldStream(lead + 1, terminal, 2048, tuning),
                "a vehicle at 78 blocks a second does not stream past the lead");
        expect(
                helper,
                SeamStreaming.shouldStream(half, terminal / 2, 2048, tuning),
                "half the speed streams at half the lead");
        expect(
                helper,
                !SeamStreaming.shouldStream(half + 1, terminal / 2, 2048, tuning),
                "half the speed does not stream past half the lead");
        expect(
                helper,
                SeamStreaming.shouldStream(lead, terminal * 2, 2048, tuning),
                "a vehicle faster than the design speed streams no farther than the lead, and no less");
        expect(
                helper,
                !SeamStreaming.shouldStream(lead + 1, terminal * 2, 2048, tuning),
                "a vehicle faster than the design speed does not stream past the lead");
        helper.succeed();
    }
}
