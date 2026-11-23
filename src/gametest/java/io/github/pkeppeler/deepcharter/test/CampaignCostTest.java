package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.test.support.BandCost;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Pool;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Ratchet;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Ratio;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Reading;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Window;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ColumnCost;
import io.github.pkeppeler.deepcharter.test.support.ColumnCost.Measurement;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures what the campaign generator (#742) costs a new column, per band (ADR 0086, which amends ADR 0029's bar). Each band's window
 * is the shipped campaign world cut to that band's rows ({@code campaign_surface}, {@code campaign_layer_1}, {@code campaign_layer_2}),
 * and {@code campaign_floor} is the same world cut to its bottom 16 rows. A band's marginal CPU per filled row is its window's pooled
 * median over the floor's, per row of the difference; {@code tall_layer} (one layer's rock and fill in every row) over
 * {@code tall_layer_floor} is the reference. The gate: every band at most {@value BandCost#BAR}x the reference, except the bands
 * {@link #RATCHETS} holds to their own measured ratio plus 15% until #1132. The whole world,
 * {@code campaign_sample} per block of height against {@code tall_baseline} (vanilla stone, no fill), is printed as a tracked number
 * and no longer gates.
 *
 * <p>By default it generates a few columns of each world and checks they exist. With the number of rounds in the environment, for
 * example {@code DEEPCHARTER_CAMPAIGN_COST=6 tools/gametest.sh 'campaign_cost_test*'}, it takes that many readings of each world,
 * alternating which is first, and prints them as {@code [campaign-cost]} lines; a run needs at least {@value #MIN_ROUNDS} rounds.
 * {@value #POOL_ENV}=<absolute file> pools runs: each run appends its readings to the file and reads every run's back, and the gate
 * judges the pool once it holds {@value BandCost#RUNS} runs and {@value BandCost#ROUNDS} rounds of each world it reads
 * ({@link BandCost}). A pool short of that prints the ratios and asserts nothing. {@value #ONLY_ENV}=<world> (or a comma-separated
 * list) measures only those worlds.
 */
public class CampaignCostTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(CampaignCostTest.class);
    static final String ROUNDS_ENV = "DEEPCHARTER_CAMPAIGN_COST";
    static final String ONLY_ENV = "DEEPCHARTER_CAMPAIGN_COST_ONLY";
    static final String POOL_ENV = "DEEPCHARTER_CAMPAIGN_COST_POOL";
    private static final String REQUESTED_ROUNDS = System.getenv(ROUNDS_ENV);
    private static final String REQUESTED_WORLD = System.getenv(ONLY_ENV);
    private static final String REQUESTED_POOL = System.getenv(POOL_ENV);
    /** This JVM's run, which a pool tells apart from the others. */
    private static final String RUN = UUID.randomUUID().toString();

    private static final String CAMPAIGN = "campaign_sample";
    /** Vanilla stone, no zone fill: the whole world's tracked ratio. */
    private static final String BASELINE = "tall_baseline";
    /** One layer's own rock with the fill in every row: the per-row cost a band is held to. */
    private static final String REFERENCE = "tall_layer";

    private static final String REFERENCE_FLOOR = "tall_layer_floor";
    /** The campaign world's bottom 16 rows, unbuilt depth: what a column of the campaign generator costs apart from its rows. */
    private static final String FLOOR = "campaign_floor";

    private static final int FLOOR_ROWS = 16;

    /** Every band but unbuilt depth, which is no band of content. */
    private static final List<Window> WINDOWS = List.of(
            new Window(CampaignBands.SURFACE.name(), "campaign_surface"),
            new Window(CampaignBands.LAYER_1.name(), "campaign_layer_1"),
            new Window(CampaignBands.LAYER_2.name(), "campaign_layer_2"));

    /**
     * The bands that were over the bar when the gate was written (ADR 0086): each is held to its pooled ratio of #1129 plus the bar's
     * 15%, so it cannot get worse. #1132 makes the campaign generator's layer bands cost about one layer per row and deletes these; a
     * band added since gets no entry and is held to {@value BandCost#BAR}x.
     */
    private static final List<Ratchet> RATCHETS = List.of(
            // #1132: layer 1's rows cost more in the campaign generator than in its own dimension; delete once it is
            // fixed
            new Ratchet(CampaignBands.LAYER_1.name(), 1.583),
            // #1132: as layer 1
            new Ratchet(CampaignBands.LAYER_2.name(), 1.834));

    /** The worlds the gate reads. */
    private static final List<String> GATED = Stream.concat(
                    WINDOWS.stream().map(Window::world), Stream.of(FLOOR, REFERENCE, REFERENCE_FLOOR))
            .toList();

    private static final List<String> ALL_WORLDS =
            Stream.concat(Stream.of(BASELINE, CAMPAIGN), GATED.stream()).toList();
    /** The worlds whose zone fill has rock to work on. */
    private static final List<String> FILLED_WORLDS =
            List.of(REFERENCE, CAMPAIGN, "campaign_layer_1", "campaign_layer_2");

    private static final List<String> WORLDS =
            REQUESTED_WORLD == null ? ALL_WORLDS : List.of(REQUESTED_WORLD.split(","));
    /** A chunk of each world's own, away from the origin that the measured columns centre on. */
    private static final int CHUNK = 600;
    /** A chunk no other check of this class generates, so both worlds make it fresh. */
    private static final int SAME_ROWS_CHUNK = 700;

    private static final int MIN_ROUNDS = 4;
    private static final int MAX_TICKS = 200_000;
    /**
     * Four hours: each tick a measured run waits on sleeps {@link FarChunks#POLL_MILLIS}, and a judged run takes about 20 minutes. Only
     * the measured run, which {@value #ROUNDS_ENV} opts into and CI never sets, has it; the smoke case keeps {@link #MAX_TICKS}.
     */
    private static final int MEASURED_MAX_TICKS = 4 * 3600 * 1000 / FarChunks.POLL_MILLIS;

    /** The smoke case, which CI runs: a few columns of each world. */
    @GameTest(maxTicks = MAX_TICKS)
    public void everyCostWorldGeneratesColumns(GameTestHelper helper) {
        ColumnCost.run(helper, WORLDS, ALL_WORLDS, 0, results -> {});
    }

    /** The measured run and the gate; without {@value #ROUNDS_ENV} it succeeds at once. */
    @GameTest(maxTicks = MEASURED_MAX_TICKS)
    public void eachBandCostsWhatALayerDoesPerRow(GameTestHelper helper) {
        if (REQUESTED_ROUNDS == null) {
            helper.succeed();
            return;
        }
        int rounds = Integer.parseInt(REQUESTED_ROUNDS);
        expect(helper, rounds >= MIN_ROUNDS, "%s needs at least %s rounds, got %s", ROUNDS_ENV, MIN_ROUNDS, rounds);
        ColumnCost.run(helper, WORLDS, ALL_WORLDS, rounds, results -> report(helper, results));
    }

    /**
     * A window's rows are the campaign world's, block for block, in a fresh column of both: cutting the height off changes nothing the
     * generator makes in the band, so a window costs what its band does in the whole world.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void eachWindowHoldsTheCampaignWorldsRows(GameTestHelper helper) {
        LevelChunk whole = world(helper, CAMPAIGN).getChunk(SAME_ROWS_CHUNK, SAME_ROWS_CHUNK);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Window window : WINDOWS) {
            ServerLevel level = world(helper, window.world());
            LevelChunk cut = level.getChunk(SAME_ROWS_CHUNK, SAME_ROWS_CHUNK);
            int differ = 0;
            // level-height: a window is a test world cut to a band's rows, and every row it has is compared
            for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        pos.set(whole.getPos().getBlockX(x), y, whole.getPos().getBlockZ(z));
                        if (!whole.getBlockState(pos).equals(cut.getBlockState(pos))) {
                            differ++;
                        }
                    }
                }
            }
            expect(helper, differ == 0, "%s blocks of %s differ from %s's", differ, window.world(), CAMPAIGN);
        }
        helper.succeed();
    }

    /** The windows and the reference put ore in a fresh column, so a band's rows are compared with a fill's (#743). */
    @GameTest(maxTicks = MAX_TICKS)
    public void theCostWorldsRunTheZoneFill(GameTestHelper helper) {
        for (String world : FILLED_WORLDS) {
            ServerLevel level = world(helper, world);
            // Not the column of the colony or of another test's cell: the middle of the sample, as ColumnCost samples
            // it.
            LevelChunk chunk = level.getChunk(CHUNK, CHUNK);
            boolean ore = false;
            for (LevelChunkSection section : chunk.getSections()) {
                ore |= section.maybeHas(
                        state -> OreRegistry.typeOf(state.getBlock()).isPresent());
            }
            expect(helper, ore, "%s has no ore in a fresh column", world);
        }
        helper.succeed();
    }

    /**
     * Each window is the shipped campaign world, generator and type, with only the type's height cut to its band's rows (from the band
     * table), and every band of content has one. A layer's window has no skylight, as the reference has none: in the campaign world a
     * layer lies under the surface's ground, and a sky over the window would light rows the campaign world keeps dark. The floors are
     * the campaign world and {@code tall_layer} cut to their bottom {@value #FLOOR_ROWS} rows.
     */
    @GameTest
    public void theWindowsAreTheCampaignWorldCutToTheirBands(GameTestHelper helper) throws IOException {
        List<String> bands = CampaignBands.ALL.stream()
                .filter(band -> band != CampaignBands.UNBUILT)
                .map(CampaignBands.Band::name)
                .toList();
        List<String> windowed = WINDOWS.stream().map(Window::band).toList();
        expect(helper, windowed.equals(bands), "windows for %s, expected one for each of %s", windowed, bands);
        for (Ratchet ratchet : RATCHETS) {
            expect(helper, windowed.contains(ratchet.band()), "a ratchet for %s, which has no window", ratchet.band());
        }
        JsonObject dimensions = read(helper, "/data/minecraft/worldgen/world_preset/flat_all_dimensions.json")
                .getAsJsonObject()
                .getAsJsonObject("dimensions");
        JsonElement campaign = read(helper, "/data/minecraft/dimension/overworld.json")
                .getAsJsonObject()
                .get("generator");
        JsonObject campaignType =
                read(helper, "/data/minecraft/dimension_type/overworld.json").getAsJsonObject();
        for (Window window : WINDOWS) {
            CampaignBands.Band band = CampaignBands.ALL.stream()
                    .filter(candidate -> candidate.name().equals(window.band()))
                    .findFirst()
                    .orElseThrow();
            expectCut(
                    helper,
                    dimensions,
                    window.world(),
                    campaign,
                    cut(campaignType, band.minY(), band.height(), band == CampaignBands.SURFACE));
        }
        expectCut(
                helper,
                dimensions,
                FLOOR,
                campaign,
                cut(campaignType, CampaignBands.UNBUILT.minY(), FLOOR_ROWS, false));
        JsonObject referenceType = read(helper, "/data/deepcharter/dimension_type/tall_baseline.json")
                .getAsJsonObject();
        expectCut(
                helper,
                dimensions,
                REFERENCE_FLOOR,
                dimensions.getAsJsonObject("deepcharter:" + REFERENCE).get("generator"),
                cut(referenceType, referenceType.get("min_y").getAsInt(), FLOOR_ROWS, false));
        helper.succeed();
    }

    private static void expectCut(
            GameTestHelper helper, JsonObject dimensions, String world, JsonElement generator, JsonObject type)
            throws IOException {
        JsonObject dimension = dimensions.getAsJsonObject("deepcharter:" + world);
        expect(helper, dimension != null, "the preset has no %s", world);
        expect(
                helper,
                dimension.get("generator").equals(generator),
                "%s's generator is not the one it is cut from",
                world);
        expect(
                helper,
                dimension.get("type").getAsString().equals("deepcharter:" + world),
                "%s has the type %s, not its own",
                world,
                dimension.get("type"));
        JsonObject actual = read(helper, "/data/deepcharter/dimension_type/" + world + ".json")
                .getAsJsonObject();
        expect(
                helper,
                actual.equals(type),
                "%s's type is not its source's cut to its rows: %s, expected %s",
                world,
                actual,
                type);
    }

    /** {@code type} with only its rows and its skylight changed. */
    private static JsonObject cut(JsonObject type, int minY, int height, boolean skylight) {
        JsonObject cut = type.deepCopy();
        cut.addProperty("min_y", minY);
        cut.addProperty("height", height);
        cut.addProperty("logical_height", height);
        cut.addProperty("has_skylight", skylight);
        return cut;
    }

    private static void report(GameTestHelper helper, Map<String, List<Measurement>> results) {
        LOGGER.info(
                "[campaign-cost] {} columns per world and round, run {}; gate (ADR 0086): each band's CPU per filled row at most"
                        + " {}x {}'s",
                ColumnCost.measuredColumns(),
                RUN,
                BandCost.BAR,
                REFERENCE);
        List<Reading> readings = new ArrayList<>();
        results.forEach((world, all) -> {
            LOGGER.info(
                    "[campaign-cost] {}: CPU ms per column, in round order: {}; heap MB: {}; wall ms per column: {}; medians {} CPU,"
                            + " {} MB",
                    world,
                    all.stream().map(m -> ColumnCost.format(m.cpuMsPerColumn())).toList(),
                    all.stream().map(m -> Math.round(m.heapMb())).toList(),
                    all.stream()
                            .map(m -> ColumnCost.format(m.wallMsPerColumn()))
                            .toList(),
                    ColumnCost.format(ColumnCost.median(all).cpuMsPerColumn()),
                    Math.round(ColumnCost.median(all).heapMb()));
            all.forEach(m -> readings.add(new Reading(RUN, world, m.height(), m.cpuMsPerColumn())));
        });
        Pool pool = pool(helper, readings);
        for (String world : ALL_WORLDS) {
            if (pool.has(world)) {
                LOGGER.info(
                        "[campaign-cost] pooled {}: median {} CPU ms per column over {} rounds of {} runs, {} blocks tall",
                        world,
                        ColumnCost.format(pool.median(world)),
                        pool.rounds(world),
                        pool.runs(world),
                        pool.height(world));
            }
        }
        if (pool.has(CAMPAIGN) && pool.has(BASELINE)) {
            double perColumn = pool.median(CAMPAIGN) / pool.median(BASELINE);
            LOGGER.info(
                    "[campaign-cost] whole world (tracked, not a gate): {} over {}, {}x per column, {}x per block of height",
                    CAMPAIGN,
                    BASELINE,
                    ColumnCost.format(perColumn),
                    ColumnCost.format(perColumn * pool.height(BASELINE) / pool.height(CAMPAIGN)));
        }
        if (!GATED.stream().allMatch(pool::has)) {
            return;
        }
        LOGGER.info(
                "[campaign-cost] {}: {} CPU ms per column per row over {}",
                REFERENCE,
                String.format("%.4f", pool.marginalPerRow(REFERENCE, REFERENCE_FLOOR)),
                REFERENCE_FLOOR);
        List<Ratio> ratios = BandCost.ratios(pool, WINDOWS, RATCHETS, FLOOR, REFERENCE, REFERENCE_FLOOR);
        for (Ratio ratio : ratios) {
            LOGGER.info(
                    "[campaign-cost] band {}: {} CPU ms per column per row over {}, {}x {}'s; at most {}x{} ({})",
                    ratio.band(),
                    String.format("%.4f", ratio.marginal()),
                    FLOOR,
                    String.format("%.3f", ratio.ratio()),
                    REFERENCE,
                    String.format("%.3f", ratio.bar()),
                    ratio.bar() == BandCost.BAR ? "" : ", a ratchet (#1132)",
                    ratio.over() ? "over" : "within");
        }
        if (!pool.judged(GATED)) {
            LOGGER.info(
                    "[campaign-cost] not judged: the gate needs {} runs of {} rounds in all, pooled through {}",
                    BandCost.RUNS,
                    BandCost.ROUNDS,
                    POOL_ENV);
            return;
        }
        List<Ratio> over = ratios.stream().filter(Ratio::over).toList();
        LOGGER.info("[campaign-cost] judged: {}", over.isEmpty() ? "every band within its bar" : "over: " + over);
        expect(helper, over.isEmpty(), "bands over their bar against %s: %s", REFERENCE, over);
    }

    /** This run's readings, with every earlier run's in the pool file when there is one. */
    private static Pool pool(GameTestHelper helper, List<Reading> readings) {
        if (REQUESTED_POOL == null) {
            return new Pool(readings);
        }
        Path file = Path.of(REQUESTED_POOL);
        try {
            Pool.append(file, readings);
            return Pool.read(file);
        } catch (IOException e) {
            throw failure(helper, "cannot pool the readings through %s: %s", file, e.getMessage());
        }
    }

    private static ServerLevel world(GameTestHelper helper, String world) {
        return ClientLight.serverLevel(
                helper.getLevel().getServer(),
                ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", world)));
    }

    private static JsonElement read(GameTestHelper helper, String resource) throws IOException {
        try (var stream = CampaignCostTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw failure(helper, "missing resource %s", resource);
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }
}
