package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ColumnCost;
import io.github.pkeppeler.deepcharter.test.support.ColumnCost.Measurement;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures what the campaign generator (#742) costs a new column (ADR 0029's bar: 1.15x). The campaign world, {@code campaign_sample} (the
 * surface, two layers and the unbuilt rock on our own generator, 2352 blocks), is read against two baselines of 2048 blocks:
 *
 * <ul>
 *   <li>{@code tall_baseline}, vanilla stone with no layer rock, where the zone fill places nothing. The bar is judged on this strict
 *       comparison, which is the one #742 used.
 *   <li>{@code tall_layer}, one layer's noise in the layer's own rock, so the zone fill has rock to work on in every row. It is reported
 *       as context: the fill covers every row there but only the layers' rows in the campaign world, so its ratio reads low.
 * </ul>
 *
 * By default it generates a few columns of each world and checks they exist. With the number of rounds in the environment, for example
 * {@code DEEPCHARTER_CAMPAIGN_COST=4 tools/gametest.sh 'campaign_cost_test*'}, it takes that many readings of each world, alternating
 * which is first, prints every reading as a {@code [campaign-cost]} line and fails when the campaign's median CPU per block of height is
 * over the bar against {@code tall_baseline}. It is per block because the campaign world is 2352 blocks tall and the baseline 2048,
 * which is 1.148x already: a per-column bar would leave the surface nothing. It needs at least {@value #MIN_ROUNDS} rounds, because a
 * median of fewer is a guess. {@value #ONLY_ENV}=<world> (or a comma-separated list of the worlds) measures only those, so that a
 * single world's heap is read in a JVM that held no other measured world; a run that leaves out the campaign world or
 * {@code tall_baseline} prints readings and asserts nothing.
 */
public class CampaignCostTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(CampaignCostTest.class);
    static final String ROUNDS_ENV = "DEEPCHARTER_CAMPAIGN_COST";
    static final String ONLY_ENV = "DEEPCHARTER_CAMPAIGN_COST_ONLY";
    private static final String REQUESTED_ROUNDS = System.getenv(ROUNDS_ENV);
    private static final String REQUESTED_WORLD = System.getenv(ONLY_ENV);

    private static final String CAMPAIGN = "campaign_sample";
    /** Vanilla stone, no zone fill: the bar's comparison. */
    private static final String BASELINE = "tall_baseline";
    /** The layer's own rock, the zone fill in every row: context for the bar. */
    private static final String FILLED_BASELINE = "tall_layer";

    private static final List<String> ALL_WORLDS = List.of(BASELINE, FILLED_BASELINE, CAMPAIGN);
    /** The worlds whose zone fill has rock to work on. */
    private static final List<String> FILLED_WORLDS = List.of(FILLED_BASELINE, CAMPAIGN);

    private static final List<String> WORLDS =
            REQUESTED_WORLD == null ? ALL_WORLDS : List.of(REQUESTED_WORLD.split(","));
    /** A chunk of each world's own, away from the origin that the measured columns centre on. */
    private static final int CHUNK = 600;

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

    /** The campaign world and {@code tall_layer} put ore in a fresh column, so the context ratio compares a fill with a fill (#743). */
    @GameTest(maxTicks = MAX_TICKS)
    public void theCostWorldsBothRunTheZoneFill(GameTestHelper helper) {
        for (String world : FILLED_WORLDS) {
            ServerLevel level = ClientLight.serverLevel(
                    helper.getLevel().getServer(),
                    ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", world)));
            // Not the column of the colony or of another test's cell: the middle of the sample, as ColumnCost samples
            // it.
            LevelChunk chunk = level.getChunk(CHUNK, CHUNK);
            boolean ore = false;
            for (LevelChunkSection section : chunk.getSections()) {
                ore |= section.maybeHas(
                        state -> OreRegistry.typeOf(state.getBlock()).isPresent());
            }
            if (!ore) {
                throw helper.assertionException(Component.literal(world + " has no ore in a fresh column"));
            }
        }
        helper.succeed();
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
        if (campaign == null) {
            return;
        }
        LOGGER.info(
                "[campaign-cost] zone fill rows: {} of {} in {}, {} of {} in {}, {} of {} in {}",
                fillRows(helper, CAMPAIGN),
                height(helper, CAMPAIGN),
                CAMPAIGN,
                fillRows(helper, FILLED_BASELINE),
                height(helper, FILLED_BASELINE),
                FILLED_BASELINE,
                0,
                height(helper, BASELINE),
                BASELINE);
        Measurement campaignMedian = ColumnCost.median(campaign);
        for (String baseline : List.of(BASELINE, FILLED_BASELINE)) {
            if (!results.containsKey(baseline)) {
                continue;
            }
            Measurement baselineMedian = ColumnCost.median(results.get(baseline));
            double perColumn = campaignMedian.cpuMsPerColumn() / baselineMedian.cpuMsPerColumn();
            double perBlock = campaignMedian.cpuMsPerBlock() / baselineMedian.cpuMsPerBlock();
            double heap = campaignMedian.heapMb() / baselineMedian.heapMb();
            LOGGER.info(
                    "[campaign-cost] {} vs {} ({}): CPU {}x per column, {}x per block of height ({} against {} blocks), heap {}x",
                    CAMPAIGN,
                    baseline,
                    baseline.equals(BASELINE) ? "the bar" : "context",
                    ColumnCost.format(perColumn),
                    ColumnCost.format(perBlock),
                    campaignMedian.height(),
                    baselineMedian.height(),
                    ColumnCost.format(heap));
            if (baseline.equals(BASELINE) && perBlock > BAR) {
                throw helper.assertionException(Component.literal(CAMPAIGN + " costs " + ColumnCost.format(perBlock)
                        + "x " + BASELINE + " per block of height, over the " + BAR + " bar"));
            }
        }
    }

    private static ServerLevel world(GameTestHelper helper, String world) {
        return ClientLight.serverLevel(
                helper.getLevel().getServer(),
                ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", world)));
    }

    private static int height(GameTestHelper helper, String world) {
        return world(helper, world).getHeight();
    }

    /** The rows the zone fill covers: the layers' bands of a campaign world, the whole of a layer-only world, none of the stone baseline. */
    private static int fillRows(GameTestHelper helper, String world) {
        ServerLevel level = world(helper, world);
        List<LayerChain.Band> bands = LayerChain.campaignLayers(level);
        return bands.isEmpty()
                ? level.getHeight()
                : bands.stream().mapToInt(LayerChain.Band::height).sum();
    }
}
