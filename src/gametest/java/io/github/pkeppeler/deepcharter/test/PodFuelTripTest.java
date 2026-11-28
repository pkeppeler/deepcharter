package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.Arrival;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.Failures;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.TripPilot;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What one tank of the stock pod buys, measured in the pilot's units (issue 963): the real stock pod, the real drill and a scripted
 * pilot's inputs ({@link TripPilot}) in layer 1. Each test prints its numbers on lines that start with {@code [fuel-trip]}.
 *
 * <p>The stone is plain (no ore, no company rock), a straight bore so the figures do not depend on the seed. (A) The pad is a stand-in
 * at the height a crossing from the surface lands at the top of layer 1, so the first stretch is the braked fall to the stone.
 */
public class PodFuelTripTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodFuelTripTest.class);

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 20_000;
    private static final int Z = 7400;
    private static final int FLOOR = TestLayers.worldY(1, 103);
    /** The turnaround of a first trip, in blocks below the first stone. */
    private static final int TURNAROUND = 40;
    /** What a first tank must buy (#963): the playtest got 13 blocks and 31 s of drilling and then a stranding. */
    private static final int MIN_BLOCKS_PER_TANK = 60;

    private static final int MIN_DRILL_SECONDS_PER_TANK = 125;
    /**
     * And no more: a tank that never runs dry makes the beep, the pump and the tank ladder pointless. The playtest
     * (#1596) found that one tank almost got a pod through layer 1 (93 blocks of its 98); it now stops well short.
     */
    private static final int MAX_BLOCKS_PER_TANK = 75;

    private static final int MAX_DRILL_SECONDS_PER_TANK = 190;
    private static final double MAX_MIXED_MINUTES_PER_TANK = 4.5;
    /** Minutes of the first trip's mix of drilling, driving and climbing that a tank must last (#1596 cut it by 30%). */
    private static final double MIN_MIXED_MINUTES_PER_TANK = 2.5;
    /** Share of the tank a first trip must come home with, as slack for a slow pilot, a wrong turn and ore. */
    private static final double MIN_SPARE_SHARE = 0.1;

    /** What a full tank of each tier lasted before #1596, in seconds of each activity: tank litres over these rates. */
    private static final float OLD_IDLE_LITRES_PER_SECOND = 0.009f;

    private static final float OLD_MOVING_LITRES_PER_SECOND = 0.027f;
    private static final float OLD_DRILLING_LITRES_PER_SECOND = 0.046f;
    /** The user's call (#1596): a full tank of every tier lasts this share of the time it did, the litres unchanged. */
    private static final double TANK_TIME_SHARE = 0.7;

    private static final double TANK_TIME_TOLERANCE = 0.01;
    /** The litres at the beep over the litres the climb home takes: room for a pilot who turns round late. */
    private static final double BEEP_CLIMB_MARGIN = 2.0;
    /** A beep earlier than this share of the tank nags a pilot who has fuel to spare. */
    private static final double MAX_BEEP_PERCENT = 40.0;
    /** Each tank tier, and the reserve tank, must multiply the minutes of play by at least this. */
    private static final double MIN_TIER_GAIN = 1.25;

    /** Blocks of stone bed under the floor: less than layer 1's stone, so the crust is never reached. */
    private static final int BED = 98;

    /** Carves the bed and the air above it, seats a pilot in a new stock pod on the pad, and runs {@code script} until it finishes. */
    private static void launch(GameTestHelper helper, int x, List<Integer> sideDigsAt, Consumer<TripPilot> onFinish) {
        ServerLevel level = TestLayers.level(helper, 1);
        int padY = TestLayers.band(level, 1).maxY() - 3;
        RoomCarver.carve(
                level, x - 3, x + 3 + TripPilot.SIDE_BLOCKS, FLOOR - BED - 1, FLOOR - 1, Z - 3, Z + 3, Blocks.STONE);
        RoomCarver.carve(level, x - 3, x + 3 + TripPilot.SIDE_BLOCKS, FLOOR, padY + 3, Z - 3, Z + 3, Blocks.AIR);
        Vec3 pad = new Vec3(x, padY, Z);
        MockPlayer pilot = MockPlayers.join(helper, "fuel-trip-" + x);
        pilot.teleportTo(level, pad, -90f, 0f);
        Arrival<TripPilot> script = new Arrival<>("script", "the far chunk ticks");
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(pad), () -> {
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(pad);
            pod.setYRot(-90f);
            level.addFreshEntity(pod);
            if (!pilot.player().startRiding(pod)) {
                throw helper.assertionException("the pilot could not mount the pod");
            }
            script.set(new TripPilot(pod, pilot, FLOOR, padY, BED, sideDigsAt));
        });
        boolean[] done = {false};
        TestTicks.each(helper, () -> {
            if (!script.isPresent() || done[0]) {
                return;
            }
            script.get().step();
            if (script.get().finished()) {
                done[0] = true;
                onFinish.accept(script.get());
            }
        });
    }

    /**
     * (#1596) The same litres last 70% of the time in every activity, and the tank ladder is flatter (the user's pick,
     * 2026-11-28): 10, 13, 17, 22, 30, 45 and 65 L, so tier 2 no longer makes fuel a non-issue.
     */
    @GameTest
    public void theSameLitresLastSeventyPercentAsLongAndTheTankLadderIsFlatter(GameTestHelper helper) {
        PodStats stock = PodStats.base();
        expectTankLitres(helper, stock.tankLitres(), 10f);
        float[] tanks = {10f, 13f, 17f, 22f, 30f, 45f, 65f};
        for (int tier = 0; tier < tanks.length; tier++) {
            PodStats stats = EarlyRunModel.mole(0, tier, 0);
            expectTankLitres(helper, stats.tankLitres(), tanks[tier]);
            expectTime(
                    helper, tier, "idle", stats.tankLitres(), OLD_IDLE_LITRES_PER_SECOND, stats.idleLitresPerSecond());
            expectTime(
                    helper,
                    tier,
                    "moving",
                    stats.tankLitres(),
                    OLD_MOVING_LITRES_PER_SECOND,
                    stats.movingLitresPerSecond());
            expectTime(
                    helper,
                    tier,
                    "drilling",
                    stats.tankLitres(),
                    OLD_DRILLING_LITRES_PER_SECOND,
                    stats.drillingLitresPerSecond());
        }
        helper.succeed();
    }

    private static void expectTankLitres(GameTestHelper helper, float actual, float expected) {
        if (actual != expected) {
            throw Failures.failure(helper, "the tank must keep its litres: %s against %s", actual, expected);
        }
    }

    private static void expectTime(
            GameTestHelper helper, int tier, String activity, float tankLitres, float oldRate, float newRate) {
        double before = tankLitres / oldRate;
        double after = tankLitres / newRate;
        LOGGER.info("[fuel-trip] tier {} {}: a full tank lasted {} s, now {} s", tier, activity, before, after);
        if (Math.abs(after / before - TANK_TIME_SHARE) > TANK_TIME_TOLERANCE) {
            throw Failures.failure(
                    helper,
                    "a full tier %d tank should last %.0f%% of its old %.0f s %s, it lasts %.0f s (%.1f%%)",
                    tier,
                    TANK_TIME_SHARE * 100,
                    before,
                    activity,
                    after,
                    after / before * 100);
        }
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aStockTankDrillsStraightDownLayerOneForMinutes(GameTestHelper helper) {
        launch(helper, 7400, List.of(), script -> {
            LOGGER.info(
                    "[fuel-trip] straight bore: {} after {} blocks and {} s on the stone, tank {} L, fall {} L",
                    script.phase(),
                    script.blocksDown(),
                    script.secondsOnTheStone(),
                    script.tankLitres(),
                    script.descentLitres());
            if (script.phase() != TripPilot.Phase.STRANDED) {
                throw helper.assertionException(
                        "one tank should run dry before the bed of %d blocks ends, the pilot ended %s after %d blocks",
                        BED, script.phase(), script.blocksDown());
            }
            if (script.blocksDown() < MIN_BLOCKS_PER_TANK || script.secondsOnTheStone() < MIN_DRILL_SECONDS_PER_TANK) {
                throw helper.assertionException(
                        "one tank should drill at least %d blocks and %d s, it drilled %d blocks in %.0f s (it was 13 blocks and 31 s)",
                        MIN_BLOCKS_PER_TANK,
                        MIN_DRILL_SECONDS_PER_TANK,
                        script.blocksDown(),
                        script.secondsOnTheStone());
            }
            if (script.blocksDown() > MAX_BLOCKS_PER_TANK || script.secondsOnTheStone() > MAX_DRILL_SECONDS_PER_TANK) {
                throw helper.assertionException(
                        "one tank should not last more than %d s of drilling, it lasted %.0f s",
                        MAX_DRILL_SECONDS_PER_TANK, script.secondsOnTheStone());
            }
            helper.succeed();
        });
    }

    /**
     * The first trip (the issue asks "at least 3 to 4 minutes of mixed play"): the scripted pilot is quick and the trip is {@code seconds()} long, 2.5 minutes. The
     * target is read as the tank carrying at least {@value #MIN_MIXED_MINUTES_PER_TANK} minutes of this mix, with {@value #MIN_SPARE_SHARE} of it left on return, because a
     * human is slower than the script (looking at the scanner, turning, a wrong tunnel) and spends the slack. It is bounded above too. Down from the pad to {@value #TURNAROUND} blocks with a side tunnel at 20 and at the turnaround, and home on the rotor.
     * The low-fuel beep must come with the climb home still in the tank, and the tank must carry the trip's mix of work for minutes.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aStockTankCarriesAFirstTripDownAndHomeWithFuelToSpare(GameTestHelper helper) {
        launch(helper, 7430, List.of(20, TURNAROUND), script -> {
            double used = script.tankLitres() - script.litres();
            double minutesOfThisMix = script.seconds() * script.tankLitres() / used / 60;
            double beepLitres = PodTuning.DEFAULT.fuel().lowFuelPercent() / 100 * script.tankLitres();
            LOGGER.info(
                    "[fuel-trip] first trip: {} in {} s, {} of {} L left, a tank of this mix lasts {} min; fall {} L, climb home {} L in {} s; beep at {} L",
                    script.phase(),
                    script.seconds(),
                    script.litres(),
                    script.tankLitres(),
                    minutesOfThisMix,
                    script.descentLitres(),
                    script.climbLitres(),
                    script.climbSeconds(),
                    beepLitres);
            if (script.phase() != TripPilot.Phase.HOME) {
                throw helper.assertionException(
                        "the first trip should end back at the pad, it ended %s with %.2f L left",
                        script.phase(), script.litres());
            }
            if (minutesOfThisMix < MIN_MIXED_MINUTES_PER_TANK) {
                throw helper.assertionException(
                        "a first tank should carry %.1f minutes of this trip's mix, it carries %.1f",
                        MIN_MIXED_MINUTES_PER_TANK, minutesOfThisMix);
            }
            if (minutesOfThisMix > MAX_MIXED_MINUTES_PER_TANK) {
                throw helper.assertionException(
                        "a first tank should not carry more than %.1f minutes of this trip's mix, it carries %.1f",
                        MAX_MIXED_MINUTES_PER_TANK, minutesOfThisMix);
            }
            if (script.litres() < MIN_SPARE_SHARE * script.tankLitres()) {
                throw helper.assertionException(
                        "the first trip should be home with %.0f%% of the tank left, it has %.0f%%",
                        MIN_SPARE_SHARE * 100, script.litres() / script.tankLitres() * 100);
            }
            if (beepLitres < BEEP_CLIMB_MARGIN * script.climbLitres()) {
                throw helper.assertionException(
                        "the beep at %.2f L should leave %.1f times the %.2f L that the climb home takes from %d blocks down",
                        beepLitres, BEEP_CLIMB_MARGIN, script.climbLitres(), TURNAROUND);
            }
            if (PodTuning.DEFAULT.fuel().lowFuelPercent() > MAX_BEEP_PERCENT) {
                throw helper.assertionException(
                        "the beep sounds from %.0f%%, which is too early: %.0f%% at most",
                        PodTuning.DEFAULT.fuel().lowFuelPercent(), MAX_BEEP_PERCENT);
            }
            // The ladder: what each tank tier and the reserve tank add to the minutes of this mix.
            double burnPerMinute = used / (script.seconds() / 60);
            double previous = minutesOfThisMix;
            for (int tier = 1; tier <= ComponentTrack.FUEL_TANK.maxTier(); tier++) {
                double minutes = EarlyRunModel.mole(0, tier, 0).tankLitres() / burnPerMinute;
                LOGGER.info("[fuel-trip] tank tier {}: {} min of this mix", tier, minutes);
                if (minutes < previous * MIN_TIER_GAIN) {
                    throw helper.assertionException(
                            "tank tier %d should buy at least %.1f times the minutes of tier %d: %.1f against %.1f",
                            tier, MIN_TIER_GAIN, tier - 1, minutes, previous);
                }
                previous = minutes;
            }
            double withReserve = (script.tankLitres() + FuelTuning.DEFAULT.reserveLitres()) / burnPerMinute;
            LOGGER.info("[fuel-trip] with the reserve tank: {} min of this mix", withReserve);
            if (withReserve < minutesOfThisMix * MIN_TIER_GAIN) {
                throw helper.assertionException(
                        "the reserve tank should buy at least %.1f times the minutes: %.1f against %.1f",
                        MIN_TIER_GAIN, withReserve, minutesOfThisMix);
            }
            helper.succeed();
        });
    }
}
