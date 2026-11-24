package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.test.support.BandCost;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Pool;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Ratchet;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Ratio;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Reading;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** The arithmetic of ADR 0086's campaign cost gate ({@link BandCost}), on made-up readings: no world is generated. */
public class BandCostTest {
    private static final double EPSILON = 1e-9;
    private static final String TREE = "tree";
    private static final int COLUMNS = 289;
    private static final String REFERENCE = "reference";
    private static final String REFERENCE_FLOOR = "reference_floor";
    private static final int FLOOR_ROWS = 16;
    private static final int REFERENCE_ROWS = 2048;
    private static final int BAND_ROWS = 192;
    private static final CampaignBands.Band BAND = new CampaignBands.Band("band", -256, BAND_ROWS);
    private static final CampaignBands.Band OLDER_BAND = new CampaignBands.Band("older band", -64, 384);
    private static final Window WINDOW = new Window(BAND, "band", "band_floor");
    /** The reference's CPU ms per column for each row over its floor. */
    private static final double REFERENCE_PER_ROW = 0.1;

    private static final List<String> GATED = List.of(WINDOW.world(), WINDOW.floor(), REFERENCE, REFERENCE_FLOOR);

    /** Pooled, the median is taken over every round of every run, not over the runs' own medians. */
    @GameTest
    public void thePooledMedianIsTakenOverEveryRound(GameTestHelper helper) {
        List<Reading> readings = new ArrayList<>();
        readings.addAll(rounds("a", "world", 16, 1, 2, 9));
        readings.addAll(rounds("b", "world", 16, 3, 10, 11));
        readings.addAll(rounds("c", "world", 16, 4, 12, 13));
        Pool pool = new Pool(readings);
        // The runs' medians are 2, 10 and 12, whose median is 10; the nine rounds' median is 9.
        expect(helper, near(pool.median("world"), 9), "pooled median %s, expected 9", pool.median("world"));
        expect(helper, pool.rounds("world") == 9, "rounds %s, expected 9", pool.rounds("world"));
        expect(helper, pool.runs("world").size() == 3, "runs %s, expected 3", pool.runs("world"));
        helper.succeed();
    }

    /** A band's marginal cost per filled row is its window's cost over its floor's, per row of the difference in height. */
    @GameTest
    public void theMarginalCostIsPerRowOverTheFloor(GameTestHelper helper) {
        Pool pool = new Pool(gatedPool(3, 6, 1.0));
        // 18 ms of floor, plus 176 rows at 0.1 ms over it.
        double marginal = pool.marginalPerRow(WINDOW.world(), WINDOW.floor());
        expect(helper, near(marginal, REFERENCE_PER_ROW), "marginal %s, expected %s", marginal, REFERENCE_PER_ROW);
        double reference = pool.marginalPerRow(REFERENCE, REFERENCE_FLOOR);
        expect(helper, near(reference, REFERENCE_PER_ROW), "reference %s, expected %s", reference, REFERENCE_PER_ROW);
        helper.succeed();
    }

    @GameTest
    public void aBandUnderTheBarPasses(GameTestHelper helper) {
        Ratio ratio = onlyRatio(new Pool(gatedPool(3, 6, 1.14)));
        expect(helper, near(ratio.ratio(), 1.14), "ratio %s, expected 1.14", ratio.ratio());
        expect(helper, !ratio.over(), "1.14x is over the %s bar", BandCost.BAR);
        helper.succeed();
    }

    /** At exactly the bar a band passes: the bar is the most a band may cost, not the least it may not. */
    @GameTest
    public void aBandExactlyAtTheBarPasses(GameTestHelper helper) {
        Ratio ratio = new Ratio(WINDOW, REFERENCE_PER_ROW, BandCost.BAR, BandCost.BAR, Optional.empty());
        expect(helper, !ratio.over(), "%sx is over the %s bar", ratio.ratio(), ratio.bar());
        helper.succeed();
    }

    /** The gate goes red: a band whose rows cost more than the bar times the reference's is over. */
    @GameTest
    public void aBandOverTheBarFails(GameTestHelper helper) {
        Ratio ratio = onlyRatio(new Pool(gatedPool(3, 6, 1.16)));
        expect(helper, near(ratio.ratio(), 1.16), "ratio %s, expected 1.16", ratio.ratio());
        expect(helper, ratio.over(), "1.16x is not over the %s bar", BandCost.BAR);
        helper.succeed();
    }

    /** A band with a ratchet is held to its measured ratio times the bar: at its ceiling it passes, and it names its issue. */
    @GameTest
    public void aRatchetedBandAtItsCeilingPasses(GameTestHelper helper) {
        Ratchet ratchet = new Ratchet(BAND, 1.5, 1132);
        // 1.725, less a hair, so floating error in the medians cannot put an exact ceiling over it.
        double ceiling = ratchet.ceiling() - 1e-9;
        Ratio ratio = onlyRatio(new Pool(gatedPool(3, 6, ceiling)), List.of(ratchet));
        expect(helper, near(ratio.bar(), 1.725), "ceiling %s, expected 1.725", ratio.bar());
        expect(helper, !ratio.over(), "%sx is over the ratchet's %s ceiling", ratio.ratio(), ratio.bar());
        expect(helper, ratio.toString().contains("#1132"), "%s does not name the ratchet's issue", ratio);
        helper.succeed();
    }

    /** The gate goes red on a ratchet too: just above its ceiling a band is over. */
    @GameTest
    public void aRatchetedBandAboveItsCeilingFails(GameTestHelper helper) {
        Ratchet ratchet = new Ratchet(BAND, 1.5, 1132);
        Ratio ratio = onlyRatio(new Pool(gatedPool(3, 6, ratchet.ceiling() + 0.001)), List.of(ratchet));
        expect(helper, ratio.over(), "%sx is not over the ratchet's %s ceiling", ratio.ratio(), ratio.bar());
        helper.succeed();
    }

    /**
     * Runs that scatter around the bar do not fail the band (#1169): the pooled ratio reads over, but the runs do not agree, so the
     * pool cannot say the band is over. The judgement is of the runs' own ratios, not of one pooled number.
     */
    @GameTest
    public void aBandWhoseRunsScatterAroundTheBarPasses(GameTestHelper helper) {
        Ratio ratio = onlyRatio(new Pool(gatedPoolOfRuns(1.0, 1.2, 1.4)));
        expect(helper, ratio.ratio() > BandCost.BAR, "the pooled ratio %s is not over the bar", ratio.ratio());
        expect(helper, !ratio.over(), "runs of 1.0x, 1.2x and 1.4x are over the %s bar", BandCost.BAR);
        helper.succeed();
    }

    /** The lower bound is the mean less t times the standard error: 1.2 less 1.886 times 0.2 over the square root of 3. */
    @GameTest
    public void theLowerBoundIsTheMeanLessTheErrorOfTheMean(GameTestHelper helper) {
        double bound = BandCost.lowerBound(new double[] {1.0, 1.2, 1.4});
        expect(helper, Math.abs(bound - 0.98222) < 1e-4, "bound %s, expected 0.98222", bound);
        expect(helper, near(BandCost.lowerBound(new double[] {1.5}), 1.5), "one run is its own bound");
        expect(helper, near(BandCost.lowerBound(new double[] {1.3, 1.3, 1.3}), 1.3), "steady runs are their mean");
        helper.succeed();
    }

    /** The t comes from the table by the number of runs: 3.078 for two, 1.638 for four, and the 10-degree value from eleven. */
    @GameTest
    public void theLowerBoundTakesItsTFromTheNumberOfRuns(GameTestHelper helper) {
        // Two runs: mean 1.1, standard error 0.1, t 3.078.
        double two = BandCost.lowerBound(new double[] {1.0, 1.2});
        expect(helper, Math.abs(two - (1.1 - 0.3078)) < 1e-9, "two runs: %s, expected 0.7922", two);
        // Four runs: mean 1.15, standard deviation 0.1291, t 1.638.
        double four = BandCost.lowerBound(new double[] {1.0, 1.1, 1.2, 1.3});
        double expectedFour = 1.15 - 1.638 * Math.sqrt(0.0166666666667 / 4);
        expect(helper, Math.abs(four - expectedFour) < 1e-6, "four runs: %s, expected %s", four, expectedFour);
        // Eleven runs, 1.0 to 2.0 in steps of 0.1: mean 1.5, standard error 0.1, t 1.372 (the fallback).
        double[] eleven = new double[11];
        for (int i = 0; i < eleven.length; i++) {
            eleven[i] = 1.0 + 0.1 * i;
        }
        double fallback = BandCost.lowerBound(eleven);
        expect(helper, Math.abs(fallback - (1.5 - 0.1372)) < 1e-9, "eleven runs: %s, expected 1.3628", fallback);
        helper.succeed();
    }

    /** A run whose reference costs nothing per row, or a ratio that is not a number, fails loudly, never as a pass. */
    @GameTest
    public void aRatioThatIsNotANumberFailsLoudly(GameTestHelper helper) {
        List<Reading> readings = new ArrayList<>(gatedPoolOfRuns(1.2, 1.2, 1.2));
        // run1's reference reads below its floor, so its marginal is negative while the pooled one is not.
        readings.replaceAll(reading -> reading.run().equals("run1")
                        && reading.world().equals(REFERENCE)
                ? new Reading(reading.run(), reading.tree(), reading.columns(), reading.world(), reading.height(), 1)
                : reading);
        try {
            onlyRatio(new Pool(readings));
            throw failure(helper, "a run with a reference of no cost per row is judged");
        } catch (IllegalStateException e) {
            expect(helper, String.valueOf(e.getMessage()).contains("run1"), "the failure does not name the run: %s", e);
        }
        try {
            BandCost.lowerBound(new double[] {1.2, Double.NaN, 1.2});
            throw failure(helper, "a NaN ratio gives a bound");
        } catch (IllegalStateException e) {
            // expected
        }
        helper.succeed();
    }

    /** A pool short of a world in some run (not judged) still has its ratios printed, with the pooled ratio as the bound. */
    @GameTest
    public void aPoolWithARunMissingAFloorStillHasRatios(GameTestHelper helper) {
        List<Reading> readings = new ArrayList<>(gatedPoolOfRuns(1.2, 1.2, 1.2));
        readings.removeIf(
                reading -> reading.run().equals("run1") && reading.world().equals(WINDOW.floor()));
        Ratio ratio = onlyRatio(new Pool(readings));
        expect(helper, !new Pool(readings).judged(GATED), "a pool with a run short of a floor is judged");
        expect(helper, Double.isFinite(ratio.bound()), "bound %s", ratio.bound());
        helper.succeed();
    }

    /** Runs that all read well over the bar fail it, however they scatter. */
    @GameTest
    public void aBandWhoseRunsAllReadOverTheBarFails(GameTestHelper helper) {
        expect(
                helper,
                onlyRatio(new Pool(gatedPoolOfRuns(1.30, 1.32, 1.28))).over(),
                "steady runs of about 1.3x are not over the %s bar",
                BandCost.BAR);
        expect(
                helper,
                onlyRatio(new Pool(gatedPoolOfRuns(1.35, 1.45, 1.55))).over(),
                "scattered runs of about 1.45x are not over the %s bar",
                BandCost.BAR);
        helper.succeed();
    }

    /** A ratchet is judged the same way: runs scattered around its ceiling pass it, steady runs over it fail. */
    @GameTest
    public void aRatchetIsJudgedOnTheRunsToo(GameTestHelper helper) {
        Ratchet ratchet = new Ratchet(BAND, 1.5, 1132);
        expect(
                helper,
                !onlyRatio(new Pool(gatedPoolOfRuns(1.5, 1.8, 2.0)), List.of(ratchet))
                        .over(),
                "runs of 1.5x, 1.8x and 2.0x are over a ceiling of %s",
                ratchet.ceiling());
        expect(
                helper,
                onlyRatio(new Pool(gatedPoolOfRuns(1.9, 1.95, 1.9)), List.of(ratchet))
                        .over(),
                "steady runs of 1.9x are not over a ceiling of %s",
                ratchet.ceiling());
        helper.succeed();
    }

    /** A band with no ratchet of its own, a new band, is held to the bar, whatever the other bands' ratchets. */
    @GameTest
    public void aNewBandIsHeldToTheBar(GameTestHelper helper) {
        List<Ratchet> others = List.of(new Ratchet(OLDER_BAND, 1.5, 1132));
        Ratio over = onlyRatio(new Pool(gatedPool(3, 6, 1.16)), others);
        expect(helper, over.ratchet().isEmpty(), "a new band took %s", over.ratchet());
        expect(helper, near(over.bar(), BandCost.BAR), "bar %s, expected %s", over.bar(), BandCost.BAR);
        expect(helper, over.over(), "1.16x is not over the %s bar beside another band's ratchet", BandCost.BAR);
        Ratio under = onlyRatio(new Pool(gatedPool(3, 6, 1.14)), others);
        expect(helper, !under.over(), "1.14x is over the %s bar beside another band's ratchet", BandCost.BAR);
        helper.succeed();
    }

    /** The gate judges only a pool of {@value BandCost#RUNS} runs and {@value BandCost#ROUNDS} rounds of every world it reads. */
    @GameTest
    public void aPoolIsJudgedOnlyWhenFull(GameTestHelper helper) {
        expect(helper, new Pool(gatedPool(3, 6, 1.0)).judged(GATED), "3 runs of 6 rounds are not judged");
        expect(helper, !new Pool(gatedPool(2, 9, 1.0)).judged(GATED), "2 runs are judged");
        expect(helper, !new Pool(gatedPool(3, 5, 1.0)).judged(GATED), "15 rounds are judged");
        List<Reading> missing = new ArrayList<>(gatedPool(3, 6, 1.0));
        missing.removeIf(reading -> reading.world().equals(WINDOW.floor()));
        expect(helper, !new Pool(missing).judged(GATED), "a pool with no floor is judged");
        helper.succeed();
    }

    /** Every world the gate reads must come from the same runs, so a window is never judged against a floor from other runs. */
    @GameTest
    public void aPoolWhoseWorldsComeFromOtherRunsIsNotJudged(GameTestHelper helper) {
        List<Reading> readings = new ArrayList<>(gatedPool(3, 6, 1.0));
        readings.replaceAll(reading -> reading.world().equals(WINDOW.world())
                ? new Reading(
                        "other " + reading.run(),
                        reading.tree(),
                        reading.columns(),
                        reading.world(),
                        reading.height(),
                        reading.cpuMsPerColumn())
                : reading);
        expect(helper, !new Pool(readings).judged(GATED), "a window from other runs than its floor is judged");
        helper.succeed();
    }

    /** Every run of the pool must have as many rounds of each world, so no run's regions weigh more than another's. */
    @GameTest
    public void aPoolWithUnequalRunsIsNotJudged(GameTestHelper helper) {
        List<Reading> readings = new ArrayList<>(gatedPool(3, 6, 1.0));
        for (String world : GATED) {
            readings.add(new Reading("run0", TREE, COLUMNS, world, new Pool(readings).height(world), 1));
        }
        expect(helper, !new Pool(readings).judged(GATED), "runs of 7, 6 and 6 rounds are judged");
        helper.succeed();
    }

    /** A pool holds one tree, one size of round and one height per world, or it is refused. */
    @GameTest
    public void aMixedPoolIsRefused(GameTestHelper helper) {
        refuses(
                helper,
                "two trees",
                () -> new Pool(List.of(
                        new Reading("a", "one", COLUMNS, "world", 16, 1),
                        new Reading("b", "two", COLUMNS, "world", 16, 1))));
        refuses(
                helper,
                "two sizes of round",
                () -> new Pool(List.of(
                        new Reading("a", TREE, COLUMNS, "world", 16, 1), new Reading("b", TREE, 81, "world", 16, 1))));
        refuses(
                helper,
                "a world of two heights",
                () -> new Pool(List.of(
                        new Reading("a", TREE, COLUMNS, "world", 16, 1),
                        new Reading("b", TREE, COLUMNS, "world", 32, 1))));
        helper.succeed();
    }

    /** Runs pool through a file: each appends its rounds, and the pool read back holds every run's; a mixed file names itself. */
    @GameTest
    public void aPoolFileHoldsEveryRunsRounds(GameTestHelper helper) throws IOException {
        Path file = Files.createTempFile("campaign-cost-pool", ".tsv");
        try {
            Files.delete(file);
            expect(helper, Pool.read(file).readings().isEmpty(), "a missing pool file is not an empty pool");
            List<Reading> first = rounds("a", "world", 2352, 150.25, 160.5);
            List<Reading> second = rounds("b", "world", 2352, 170.125);
            Pool.append(file, first);
            Pool.append(file, second);
            List<Reading> all = new ArrayList<>(first);
            all.addAll(second);
            Pool read = Pool.read(file);
            expect(helper, read.readings().equals(all), "read back %s, expected %s", read.readings(), all);
            expect(helper, read.height("world") == 2352, "height %s, expected 2352", read.height("world"));
            Pool.append(file, List.of(new Reading("c", "another tree", COLUMNS, "world", 2352, 1)));
            try {
                Pool.read(file);
                throw failure(helper, "a pool file of two trees is read");
            } catch (IllegalStateException e) {
                String message = String.valueOf(e.getMessage());
                expect(
                        helper,
                        message.contains(file.toString()) && message.contains("delete"),
                        "the refusal does not name the file and say to delete it: %s",
                        message);
            }
        } finally {
            Files.deleteIfExists(file);
        }
        helper.succeed();
    }

    private static void refuses(GameTestHelper helper, String what, Runnable pool) {
        try {
            pool.run();
        } catch (IllegalArgumentException e) {
            return;
        }
        throw failure(helper, "a pool of %s is not refused", what);
    }

    private static Ratio onlyRatio(Pool pool) {
        return onlyRatio(pool, List.of());
    }

    private static Ratio onlyRatio(Pool pool, List<Ratchet> ratchets) {
        return BandCost.ratios(pool, List.of(WINDOW), ratchets, REFERENCE, REFERENCE_FLOOR)
                .getFirst();
    }

    /**
     * {@code runs} runs of {@code rounds} rounds of the four worlds the gate reads, where the band's rows cost {@code times} the
     * reference's. Each world's rounds spread around its value, so the median is the value.
     */
    private static List<Reading> gatedPool(int runs, int rounds, double times) {
        List<Reading> readings = new ArrayList<>();
        for (int run = 0; run < runs; run++) {
            String id = "run" + run;
            readings.addAll(spread(id, WINDOW.floor(), FLOOR_ROWS, 18, rounds));
            readings.addAll(spread(
                    id, WINDOW.world(), BAND_ROWS, 18 + (BAND_ROWS - FLOOR_ROWS) * REFERENCE_PER_ROW * times, rounds));
            readings.addAll(spread(id, REFERENCE_FLOOR, FLOOR_ROWS, 20, rounds));
            readings.addAll(spread(
                    id, REFERENCE, REFERENCE_ROWS, 20 + (REFERENCE_ROWS - FLOOR_ROWS) * REFERENCE_PER_ROW, rounds));
        }
        return readings;
    }

    /** One run of 6 rounds for each of {@code times}, each run's band costing that many times the reference's. */
    private static List<Reading> gatedPoolOfRuns(double... times) {
        List<Reading> readings = new ArrayList<>();
        for (int run = 0; run < times.length; run++) {
            String id = "run" + run;
            for (Reading reading : gatedPool(1, 6, times[run])) {
                readings.add(new Reading(
                        id,
                        reading.tree(),
                        reading.columns(),
                        reading.world(),
                        reading.height(),
                        reading.cpuMsPerColumn()));
            }
        }
        return readings;
    }

    /** {@code rounds} readings symmetric around {@code value}: as many above as below, and the value itself when odd. */
    private static List<Reading> spread(String run, String world, int height, double value, int rounds) {
        List<Reading> readings = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            double offset = round % 2 == 0 ? round / 2 + 1 : -(round / 2 + 1);
            readings.add(new Reading(
                    run,
                    TREE,
                    COLUMNS,
                    world,
                    height,
                    rounds % 2 == 1 && round == rounds - 1 ? value : value + offset));
        }
        return readings;
    }

    private static List<Reading> rounds(String run, String world, int height, double... cpu) {
        List<Reading> readings = new ArrayList<>();
        for (double value : cpu) {
            readings.add(new Reading(run, TREE, COLUMNS, world, height, value));
        }
        return readings;
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < EPSILON;
    }
}
