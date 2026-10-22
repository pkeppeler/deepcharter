package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.support.ColumnCost;
import io.github.pkeppeler.deepcharter.test.support.ColumnCost.Measurement;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures what the campaign generator (#742) costs a new column against the layers alone (ADR 0029's bar: 1.15x): {@code campaign_sample}
 * (the surface, two layers and the unbuilt rock on our own generator, 2352 blocks) against {@code tall_baseline} (one layer's noise over
 * 2048 blocks). By default it generates a few columns of each and checks they exist. With the number of rounds in the environment, for example
 * {@code DEEPCHARTER_CAMPAIGN_COST=4 tools/gametest.sh 'campaign_cost_test*'}, it takes that many readings of each world, alternating which
 * is first, prints every reading as a {@code [campaign-cost]} line and fails when the campaign's median CPU per block of height is over the bar. It is per block because the campaign world is 2352 blocks tall and the baseline 2048, which is 1.148x already: a per-column bar would leave the surface nothing. The baseline still runs the zone fill that the campaign world lacks until #743, so re-measure when #743 lands.
 * It needs at least {@value #MIN_ROUNDS} rounds, because a median of fewer is a guess. {@value #ONLY_ENV}=<world> (or a comma-separated list of the worlds) measures only those, so
 * that a single world's heap is read in a JVM that held no other measured world; a run that leaves one of the two out prints readings and asserts nothing.
 */
public class CampaignCostTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(CampaignCostTest.class);
    static final String ROUNDS_ENV = "DEEPCHARTER_CAMPAIGN_COST";
    static final String ONLY_ENV = "DEEPCHARTER_CAMPAIGN_COST_ONLY";
    private static final String REQUESTED_ROUNDS = System.getenv(ROUNDS_ENV);
    private static final String REQUESTED_WORLD = System.getenv(ONLY_ENV);

    private static final String CAMPAIGN = "campaign_sample";
    private static final String BASELINE = "tall_baseline";
    private static final List<String> ALL_WORLDS = List.of(BASELINE, CAMPAIGN);
    private static final List<String> WORLDS =
            REQUESTED_WORLD == null ? ALL_WORLDS : List.of(REQUESTED_WORLD.split(","));
    private static final double BAR = 1.15;
    private static final int MIN_ROUNDS = 4;
    private static final int MAX_TICKS = 200_000;

    @GameTest(maxTicks = MAX_TICKS)
    public void theCampaignWorldCostsWhatTheLayersAloneDo(GameTestHelper helper) {
        int rounds = REQUESTED_ROUNDS == null ? 0 : Integer.parseInt(REQUESTED_ROUNDS);
        if (rounds != 0 && rounds < MIN_ROUNDS) {
            throw helper.assertionException(
                    Component.literal(ROUNDS_ENV + " needs at least " + MIN_ROUNDS + " rounds, got " + rounds));
        }
        ColumnCost.run(helper, WORLDS, ALL_WORLDS, rounds, results -> report(helper, results));
    }

    private static void report(GameTestHelper helper, Map<String, List<Measurement>> results) {
        LOGGER.info(
                "[campaign-cost] {} columns per world and round; bar: the campaign's median CPU per block of height at most {}x {}'s",
                ColumnCost.measuredColumns(),
                BAR,
                BASELINE);
        results.forEach((world, all) -> LOGGER.info(
                "[campaign-cost] {}: CPU ms per column, in round order: {}; heap MB: {}; wall ms per column: {}; medians {} CPU, {} MB",
                world,
                all.stream().map(m -> ColumnCost.format(m.cpuMsPerColumn())).toList(),
                all.stream().map(m -> Math.round(m.heapMb())).toList(),
                all.stream().map(m -> ColumnCost.format(m.wallMsPerColumn())).toList(),
                ColumnCost.format(ColumnCost.median(all).cpuMsPerColumn()),
                Math.round(ColumnCost.median(all).heapMb())));
        List<Measurement> campaign = results.get(CAMPAIGN);
        List<Measurement> baseline = results.get(BASELINE);
        if (campaign == null || baseline == null) {
            return;
        }
        Measurement campaignMedian = ColumnCost.median(campaign);
        Measurement baselineMedian = ColumnCost.median(baseline);
        double perColumn = campaignMedian.cpuMsPerColumn() / baselineMedian.cpuMsPerColumn();
        double perBlock = campaignMedian.cpuMsPerBlock() / baselineMedian.cpuMsPerBlock();
        double heap = campaignMedian.heapMb() / baselineMedian.heapMb();
        LOGGER.info(
                "[campaign-cost] {} vs {}: CPU {}x per column, {}x per block of height ({} against {} blocks), heap {}x",
                CAMPAIGN,
                BASELINE,
                ColumnCost.format(perColumn),
                ColumnCost.format(perBlock),
                campaignMedian.height(),
                baselineMedian.height(),
                ColumnCost.format(heap));
        if (perBlock > BAR) {
            throw helper.assertionException(Component.literal(CAMPAIGN + " costs " + ColumnCost.format(perBlock) + "x "
                    + BASELINE + " per block of height, over the " + BAR + " bar"));
        }
    }
}
