package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.creature.DrifterTuning;
import io.github.pkeppeler.deepcharter.creature.Drifters;
import io.github.pkeppeler.deepcharter.layer.Zones;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pacing of the Drifters (#973), rolled with the spawner's own roll ({@link Drifters#rolls}) and not restated: placements a run for each
 * zone, with the alarm and without it, and the wait between two. CREATURES.md section 4 asks for about one in a run in the top zone, and one
 * every few minutes in the bottom zone. A run is 4 minutes of a pod drilling, the figure the Retained's economy used (mechanics.md). The in-game
 * measures (drift speed, the tell, the hull in the gas) are {@link DrifterTest}'s log lines; the table is in mechanics.md.
 */
public class DrifterPacingTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(DrifterPacingTest.class);
    private static final DrifterTuning TUNING = DrifterTuning.DEFAULT;
    private static final int RUN_MINUTES = 4;
    private static final int RUNS = 20_000;
    private static final long SEED = 973;
    /** A sample of {@link #RUNS} runs lands within this of the expected count: the spread of the mean of that many runs is under 0.01. */
    private static final double TOLERANCE = 0.05;

    private static int checksPerRun() {
        return RUN_MINUTES * 60 * 20 / TUNING.spawn().checkTicks();
    }

    private static double sampledPerRun(int zone, boolean alarm, RandomSource random) {
        long placed = 0;
        for (int run = 0; run < RUNS; run++) {
            for (int check = 0; check < checksPerRun(); check++) {
                placed += Drifters.rolls(zone, alarm, random) ? 1 : 0;
            }
        }
        return placed / (double) RUNS;
    }

    /** Placements a run for one drilling pod in each zone, with and without the alarm; the alarm triples the rate and shortens the wait to a third. */
    @GameTest
    public void aRunMeetsAboutOneDrifterAtTheTopAndAFewMoreAtTheBottomAndTheAlarmTriplesIt(GameTestHelper helper) {
        RandomSource random = RandomSource.create(SEED);
        double[] quiet = new double[Zones.COUNT];
        double[] alarmed = new double[Zones.COUNT];
        LOGGER.info(
                "[drifter-pacing] a pod drilling for {} minutes: placements a run, and the wait between two",
                RUN_MINUTES);
        for (int zone = 0; zone < Zones.COUNT; zone++) {
            quiet[zone] = sampledPerRun(zone, false, random);
            alarmed[zone] = sampledPerRun(zone, true, random);
            double expected = TUNING.spawn().perMinute().get(zone) * RUN_MINUTES;
            double wait = TUNING.spawn().checkTicks() / Drifters.chance(zone, false) / 20;
            double waitAlarm = TUNING.spawn().checkTicks() / Drifters.chance(zone, true) / 20;
            LOGGER.info(
                    "[drifter-pacing] zone {}: {} a run ({} expected), one every {} s; alarmed {} a run, one every {} s",
                    zone,
                    String.format("%.2f", quiet[zone]),
                    String.format("%.2f", expected),
                    String.format("%.0f", wait),
                    String.format("%.2f", alarmed[zone]),
                    String.format("%.0f", waitAlarm));
            expect(
                    helper,
                    Math.abs(quiet[zone] - expected) < TOLERANCE,
                    "zone %d placed %s a run, not %s",
                    zone,
                    quiet[zone],
                    expected);
            expect(
                    helper,
                    Math.abs(alarmed[zone] - expected * TUNING.alarm().chanceMult())
                            < TOLERANCE * TUNING.alarm().chanceMult(),
                    "zone %d under the alarm placed %s a run, not %s times %s",
                    zone,
                    alarmed[zone],
                    TUNING.alarm().chanceMult(),
                    expected);
        }
        expect(
                helper,
                Math.abs(quiet[0] - 1.0) < 0.1,
                "the top zone places %s a run, CREATURES.md says about one",
                quiet[0]);
        expect(
                helper,
                quiet[0] < quiet[1] && quiet[1] < quiet[2],
                "the cadence does not rise down the layer: %s, %s, %s",
                quiet[0],
                quiet[1],
                quiet[2]);
        // Every few minutes at the bottom: between 1 and 5 minutes.
        double bottomWaitMinutes = TUNING.spawn().checkTicks() / Drifters.chance(2, false) / 20 / 60;
        expect(
                helper,
                bottomWaitMinutes >= 1 && bottomWaitMinutes <= 5,
                "the bottom zone places one every %s minutes",
                bottomWaitMinutes);
        helper.succeed();
    }
}
