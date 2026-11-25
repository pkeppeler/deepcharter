package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.AREA_CHUNKS;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.HOLD_HEIGHT;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.MAX_TICKS;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.arrivalTickets;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.loadedAround;

import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.SeamStreaming;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.BlockingLoads;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.SeamSite.Site;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.TickTimes;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An upward crossing finds the arrival area of the level above loaded, and loads nothing on the server thread (#1316,
 * ADR 0089): the rise through a layer's ceiling streams as the descent through a floor does. Layer 2's ceiling leads
 * into layer 1, a thousand blocks from any other seam test's column.
 */
public class SeamAscentTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(SeamAscentTest.class);
    /** Blocks under the ceiling where a streamed rise starts to move once the area is ready. */
    private static final int RUN_UP = 3;
    /** Ticks of the new layer watched after the crossing, so what the arrival does after it is in the record too. */
    private static final int AFTER_TICKS = 10;
    /** The site index of the streamed case and of the control, clear of every other seam test's. */
    private static final int STREAMED_SITE = 12;

    private static final int CONTROL_SITE = 13;
    private static final int LIFECYCLE_SITE = 14;
    /** Blocks past the floor margin where a pod is outside the zone of the ceiling. */
    private static final int OUTSIDE_ZONE = 60;

    /**
     * The shaft a mock rises in, from {@code HOLD_HEIGHT + 10} under the ceiling to the top row: not rock, so it takes
     * no damage.
     */
    private static void carveShaft(Site site, int ceilingY) {
        carveShaft(site, ceilingY, HOLD_HEIGHT + 10);
    }

    private static void carveShaft(Site site, int ceilingY, int depth) {
        BlockPos column = BlockPos.containing(site.x(), ceilingY, site.z());
        RoomCarver.carve(
                site.two(), column.offset(-2, -depth, -2), column.offset(2, 0, 2), Blocks.AIR.defaultBlockState(), 3);
    }

    private static boolean inArea(Site site, BlockingLoads.Load load) {
        int radius = LayerTuning.DEFAULT.seamRadius();
        return load.level().equals(site.one().dimension())
                && Math.abs(load.chunkX() - site.centre().x()) <= radius
                && Math.abs(load.chunkZ() - site.centre().z()) <= radius;
    }

    /**
     * A mock rising in layer 2 within the margin of its ceiling gets the whole arrival area of layer 1 loaded and
     * ticketed before it crosses, and the crossing waits for no chunk of it on the server thread.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void anUpwardCrossingFindsItsArrivalAreaLoadedAndWaitsForNoChunk(GameTestHelper helper) {
        Site site = Site.of(helper, STREAMED_SITE);
        MockPlayer mock = MockPlayers.join(helper, "seam-ascent");
        FarChunks.Deadline deadline = FarChunks.deadline();
        int ceilingY = TestLayers.dimensionBand(site.two()).maxY();
        expect(
                helper,
                HOLD_HEIGHT <= LayerTuning.DEFAULT.seamFloorMargin(),
                "the hold height %d is outside the floor margin %d",
                HOLD_HEIGHT,
                LayerTuning.DEFAULT.seamFloorMargin());
        TickTimes.restart();
        boolean[] ticking = {false};
        int[] stage = {0};
        int[] crossedAt = {-1};
        int[] loadedBefore = {-1};
        int[] ticketsBefore = {-1};
        FarChunks.awaitAllEntityTicking(
                helper,
                site.two(),
                List.of(BlockPos.containing(site.x(), ceilingY - HOLD_HEIGHT, site.z())),
                () -> ticking[0] = true);
        TestTicks.each(helper, () -> {
            if (!ticking[0]) {
                return;
            }
            if (stage[0] == 0) {
                carveShaft(site, ceilingY);
                mock.teleportTo(site.two(), new Vec3(site.x(), ceilingY - HOLD_HEIGHT, site.z()), 0, 0);
                stage[0] = 1;
                return;
            }
            if (stage[0] == 1) {
                int loaded = loadedAround(site.one(), site.centre());
                int tickets = arrivalTickets(site.one(), site.centre()).size();
                boolean ready = loaded == AREA_CHUNKS && tickets > 0;
                deadline.await(
                        helper,
                        site.one(),
                        ready,
                        () -> "a mock held " + HOLD_HEIGHT + " blocks under the ceiling of layer 2 has " + loaded
                                + " of " + AREA_CHUNKS + " arrival chunks of layer 1 loaded and " + tickets
                                + " arrival tickets");
                if (ready) {
                    mock.player().setPos(site.x(), ceilingY - RUN_UP, site.z());
                    BlockingLoads.arm(helper.getBounds());
                    TickTimes.restart();
                    stage[0] = 2;
                }
                return;
            }
            if (crossedAt[0] < 0) {
                if (TestLayers.isInDimensionOf(mock.player(), 1)) {
                    crossedAt[0] = (int) helper.getTick();
                    LOGGER.info("[seam-ascent] streamed: peak tick {} ms at the crossing", TickTimes.peakMillis());
                } else {
                    loadedBefore[0] = loadedAround(site.one(), site.centre());
                    ticketsBefore[0] = arrivalTickets(site.one(), site.centre()).size();
                    mock.player().setPos(site.x(), mock.player().getY() + 1, site.z());
                }
                return;
            }
            if (helper.getTick() - crossedAt[0] < AFTER_TICKS) {
                return;
            }
            List<BlockingLoads.Load> loads = BlockingLoads.disarm(helper.getBounds());
            List<BlockingLoads.Load> inArea =
                    loads.stream().filter(load -> inArea(site, load)).toList();
            LOGGER.info(
                    "[seam-ascent] streamed: loaded {} of {} before the crossing, {} server-thread chunk waits in the area, peak tick {} ms",
                    loadedBefore[0],
                    AREA_CHUNKS,
                    inArea.size(),
                    TickTimes.peakMillis());
            expect(
                    helper,
                    loadedBefore[0] == AREA_CHUNKS && ticketsBefore[0] > 0,
                    "the crossing found %d of %d arrival chunks loaded and %d arrival tickets",
                    loadedBefore[0],
                    AREA_CHUNKS,
                    ticketsBefore[0]);
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
     * The control of the case above: a mock that is over the ceiling the first tick it is near it has no lead, so its
     * crossing does wait for the chunks on the server thread, and says how many of the area were loaded. It proves that
     * the mixin sees a blocking load going up, and logs the stall the streamed case removes.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void anUpwardCrossingWithNoLeadWaitsForTheAreaAndSaysSo(GameTestHelper helper) {
        Site site = Site.of(helper, CONTROL_SITE);
        MockPlayer mock = MockPlayers.join(helper, "seam-ascent-no-lead");
        LogCapture log = LogCapture.start(mock.player().getUUID().toString());
        int ceilingY = TestLayers.dimensionBand(site.two()).maxY();
        TickTimes.restart();
        boolean[] ticking = {false};
        boolean[] armed = {false};
        FarChunks.awaitAllEntityTicking(
                helper,
                site.two(),
                List.of(BlockPos.containing(site.x(), ceilingY - RUN_UP, site.z())),
                () -> ticking[0] = true);
        TestTicks.each(helper, () -> {
            if (!ticking[0]) {
                return;
            }
            if (!armed[0]) {
                armed[0] = true;
                carveShaft(site, ceilingY);
                BlockingLoads.arm(helper.getBounds());
                TickTimes.restart();
                mock.teleportTo(site.two(), new Vec3(site.x(), ceilingY - RUN_UP, site.z()), 0, 0);
                mock.player().setPos(site.x(), ceilingY + 2, site.z());
                return;
            }
            if (!TestLayers.isInDimensionOf(mock.player(), 1) || BlockingLoads.disarmed(helper.getBounds())) {
                return;
            }
            List<BlockingLoads.Load> loads = BlockingLoads.disarm(helper.getBounds());
            List<BlockingLoads.Load> inArea =
                    loads.stream().filter(load -> inArea(site, load)).toList();
            LOGGER.info(
                    "[seam-ascent] no lead: {} server-thread chunk waits in the area, peak tick {} ms",
                    inArea.size(),
                    TickTimes.peakMillis());
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

    /**
     * A pod with a rider inside the zone of a ceiling has an ascent, keyed by the pod, and no entry among the
     * approaches served to riders; outside the zone it has neither, and its descent track never mixed with the rise.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aRisingPodHasAnAscentThatIsNotServedAndEndsOutsideTheZone(GameTestHelper helper) {
        Site site = Site.of(helper, LIFECYCLE_SITE);
        MockPlayer mock = MockPlayers.join(helper, "seam-ascent-pod");
        int ceilingY = TestLayers.dimensionBand(site.two()).maxY();
        int outsideDepth = LayerTuning.DEFAULT.seamFloorMargin() + OUTSIDE_ZONE;
        PodEntity[] pods = new PodEntity[1];
        boolean[] ticking = {false};
        boolean[] left = {false};
        int[] outsideTicks = {0};
        TickTimes.restart();
        FarChunks.awaitAllEntityTicking(
                helper,
                site.two(),
                List.of(BlockPos.containing(site.x(), ceilingY - HOLD_HEIGHT, site.z())),
                () -> ticking[0] = true);
        TestTicks.each(helper, () -> {
            if (!ticking[0]) {
                return;
            }
            if (pods[0] == null) {
                carveShaft(site, ceilingY, outsideDepth + 10);
                PodEntity pod = TestEntities.create(PodRegistry.POD, site.two());
                pod.setPos(site.x(), ceilingY - HOLD_HEIGHT, site.z());
                pod.setDeltaMovement(Vec3.ZERO);
                site.two().addFreshEntity(pod);
                pods[0] = pod;
                mock.teleportTo(site.two(), new Vec3(site.x(), ceilingY - HOLD_HEIGHT, site.z()), 0, 0);
                expect(helper, mock.player().startRiding(pod, true, false), "the mock could not board the pod");
                return;
            }
            PodEntity pod = pods[0];
            double y = ceilingY - (left[0] ? outsideDepth : HOLD_HEIGHT);
            pod.setPos(site.x(), y, site.z());
            pod.setDeltaMovement(Vec3.ZERO);
            if (!left[0]) {
                if (SeamStreaming.ascentOf(pod.getUUID()).isEmpty()) {
                    return;
                }
                expect(
                        helper,
                        SeamStreaming.ascentOf(pod.getUUID())
                                .orElseThrow()
                                .lower()
                                .equals(site.one().dimension()),
                        "the ascent does not stream layer 1");
                expect(
                        helper,
                        SeamStreaming.approaches().stream()
                                .noneMatch(approach -> approach.root().equals(pod.getUUID())),
                        "a rise is served to riders as an approach: %s",
                        SeamStreaming.approaches());
                expect(
                        helper,
                        SeamStreaming.approachOf(pod.getUUID()).isEmpty(),
                        "a pod at a ceiling has a descent track");
                left[0] = true;
                return;
            }
            if (++outsideTicks[0] < 5) {
                return;
            }
            expect(
                    helper,
                    SeamStreaming.ascentOf(pod.getUUID()).isEmpty(),
                    "the ascent outlived the pod leaving the zone");
            expect(
                    helper,
                    SeamStreaming.approachOf(pod.getUUID()).isEmpty(),
                    "a pod outside both zones has a descent track");
            mock.player().stopRiding();
            helper.succeed();
        });
    }
}
