package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.test.support.BandCost;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Pool;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Ratio;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Reading;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** The arithmetic of ADR 0086's campaign cost gate ({@link BandCost}), on made-up readings: no world is generated. */
public class BandCostTest {
    private static final double EPSILON = 1e-9;
    private static final String REFERENCE = "reference";
    private static final String REFERENCE_FLOOR = "reference_floor";
    private static final String FLOOR = "floor";
    private static final int FLOOR_ROWS = 16;
    private static final int REFERENCE_ROWS = 2048;
    private static final int BAND_ROWS = 192;
    /** The reference's CPU ms per column for each row over its floor. */
    private static final double REFERENCE_PER_ROW = 0.1;

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
        expect(helper, pool.runs("world") == 3, "runs %s, expected 3", pool.runs("world"));
        helper.succeed();
    }

    /** A band's marginal cost per filled row is its window's cost over the floor's, per row of the difference in height. */
    @GameTest
    public void theMarginalCostIsPerRowOverTheFloor(GameTestHelper helper) {
        Pool pool = new Pool(gatedPool(3, 6, 1.0));
        // 18 ms of floor, plus 176 rows at 0.1 ms over it.
        double marginal = pool.marginalPerRow("band", FLOOR);
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

    /** The gate goes red: a band whose rows cost more than the bar times the reference's is over. */
    @GameTest
    public void aBandOverTheBarFails(GameTestHelper helper) {
        Ratio ratio = onlyRatio(new Pool(gatedPool(3, 6, 1.16)));
        expect(helper, near(ratio.ratio(), 1.16), "ratio %s, expected 1.16", ratio.ratio());
        expect(helper, ratio.over(), "1.16x is not over the %s bar", BandCost.BAR);
        helper.succeed();
    }

    /** The gate judges only a pool of {@value BandCost#RUNS} runs and {@value BandCost#ROUNDS} rounds of every world it reads. */
    @GameTest
    public void aPoolIsJudgedOnlyWhenFull(GameTestHelper helper) {
        List<String> worlds = List.of("band", FLOOR, REFERENCE, REFERENCE_FLOOR);
        expect(helper, new Pool(gatedPool(3, 6, 1.0)).judged(worlds), "3 runs of 6 rounds are not judged");
        expect(helper, !new Pool(gatedPool(2, 9, 1.0)).judged(worlds), "2 runs are judged");
        expect(helper, !new Pool(gatedPool(3, 5, 1.0)).judged(worlds), "15 rounds are judged");
        List<Reading> missing = new ArrayList<>(gatedPool(3, 6, 1.0));
        missing.removeIf(reading -> reading.world().equals(FLOOR));
        expect(helper, !new Pool(missing).judged(worlds), "a pool with no floor is judged");
        helper.succeed();
    }

    /** Runs pool through a file: each appends its rounds, and the pool read back holds every run's. */
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
        } finally {
            Files.deleteIfExists(file);
        }
        helper.succeed();
    }

    private static Ratio onlyRatio(Pool pool) {
        List<Ratio> ratios =
                BandCost.ratios(pool, List.of(new Window("band", "band")), FLOOR, REFERENCE, REFERENCE_FLOOR);
        return ratios.getFirst();
    }

    /**
     * {@code runs} runs of {@code rounds} rounds of the four worlds the gate reads, where the band's rows cost {@code times} the
     * reference's. Each world's rounds spread around its value, so the median is the value.
     */
    private static List<Reading> gatedPool(int runs, int rounds, double times) {
        List<Reading> readings = new ArrayList<>();
        for (int run = 0; run < runs; run++) {
            String id = "run" + run;
            readings.addAll(spread(id, FLOOR, FLOOR_ROWS, 18, rounds));
            readings.addAll(
                    spread(id, "band", BAND_ROWS, 18 + (BAND_ROWS - FLOOR_ROWS) * REFERENCE_PER_ROW * times, rounds));
            readings.addAll(spread(id, REFERENCE_FLOOR, FLOOR_ROWS, 20, rounds));
            readings.addAll(spread(
                    id, REFERENCE, REFERENCE_ROWS, 20 + (REFERENCE_ROWS - FLOOR_ROWS) * REFERENCE_PER_ROW, rounds));
        }
        return readings;
    }

    /** {@code rounds} readings symmetric around {@code value}: as many above as below, and the value itself when odd. */
    private static List<Reading> spread(String run, String world, int height, double value, int rounds) {
        List<Reading> readings = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            double offset = round % 2 == 0 ? round / 2 + 1 : -(round / 2 + 1);
            readings.add(
                    new Reading(run, world, height, rounds % 2 == 1 && round == rounds - 1 ? value : value + offset));
        }
        return readings;
    }

    private static List<Reading> rounds(String run, String world, int height, double... cpu) {
        List<Reading> readings = new ArrayList<>();
        for (double value : cpu) {
            readings.add(new Reading(run, world, height, value));
        }
        return readings;
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < EPSILON;
    }
}
