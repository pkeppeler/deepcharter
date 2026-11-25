package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Pending;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Survey;
import io.github.pkeppeler.deepcharter.outpost.SettlingTuning;
import io.github.pkeppeler.deepcharter.outpost.SettlingTuning.LayerRates;
import io.github.pkeppeler.deepcharter.outpost.VisitRoll;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * #1004's pacing table: how long a dark outpost keeps its stored fuel, per layer, from the shipped rates. It plays the rule of
 * {@code OutpostSettling} (a visit takes a quarter of what is stored, one to sixteen items) window by window over 101 seeds, and prints
 * the median in days of game time as {@code [away-settling]} lines for the PR. It asserts only what the design promises: a deeper layer
 * empties a store sooner, and every store empties in the end.
 */
public class AwaySettlingPacingTest {
    private static final int SEEDS = 101;
    private static final int[] STOCKS = {64, 512, 1728};
    private static final int WINDOWS_A_DAY = SettlingTuning.TICKS_PER_DAY / SettlingTuning.DEFAULT.windowTicks();
    /** Far past the longest wait the shipped rates can give, so a store that never empties fails the test. */
    private static final int GIVE_UP_DAYS = 3650;

    /** The days until a dark outpost holding {@code stock} fuel items has none, for one outpost's seed. */
    private static int daysToEmpty(long seed, LayerRates rates, int stock) {
        SettlingTuning tuning = SettlingTuning.DEFAULT;
        int fuel = stock;
        for (long window = 0; window < (long) GIVE_UP_DAYS * WINDOWS_A_DAY; window++) {
            Survey draws = new Survey(true, 1, fuel, 0, 0);
            Pending visits = VisitRoll.roll(seed, window, window + 1, draws, rates, tuning);
            for (int visit = 0; visit < visits.retained() && fuel > 0; visit++) {
                int most = Math.clamp((long) Math.ceil(tuning.fuelShare() * fuel), 1, tuning.maxFuelItemsPerVisit());
                fuel -= Math.min(fuel, most);
            }
            if (fuel == 0) {
                return (int) Math.ceil((window + 1) / (double) WINDOWS_A_DAY);
            }
        }
        return GIVE_UP_DAYS;
    }

    @GameTest
    public void aDarkOutpostEmptiesItsFuelSoonerTheDeeperItIs(GameTestHelper helper) {
        SettlingTuning tuning = SettlingTuning.DEFAULT;
        DeepCharter.LOGGER.info(
                "[away-settling] retained visits a day per layer: {}; each takes {}% of the fuel stored, 1 to {} items",
                tuning.layers().stream().map(LayerRates::retainedPerDay).toList(),
                Math.round(tuning.fuelShare() * 100),
                tuning.maxFuelItemsPerVisit());
        for (int stock : STOCKS) {
            int previous = Integer.MAX_VALUE;
            for (int layer = 1; layer <= tuning.layers().size(); layer++) {
                List<Integer> days = new ArrayList<>();
                for (int seed = 0; seed < SEEDS; seed++) {
                    long outpostSeed = VisitRoll.outpostSeed(seed, new CharterId(new UUID(7, 7)), layer);
                    days.add(daysToEmpty(outpostSeed, tuning.ratesOf(layer), stock));
                }
                Collections.sort(days);
                int median = days.get(SEEDS / 2);
                DeepCharter.LOGGER.info(
                        "[away-settling] layer {}, {} fuel items stored in a dark outpost: empty after {} days (median of {}; fastest {}, slowest {})",
                        layer,
                        stock,
                        median,
                        SEEDS,
                        days.getFirst(),
                        days.getLast());
                expect(helper, days.getLast() < GIVE_UP_DAYS, "layer %s never emptied %s items", layer, stock);
                expect(
                        helper,
                        median < previous,
                        "layer %s should empty %s items sooner than the layer above, %s days against %s",
                        layer,
                        stock,
                        median,
                        previous);
                previous = median;
            }
        }
        helper.succeed();
    }
}
