package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.test.support.BandCost;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Pool;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Ratchet;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Ratio;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Reading;
import io.github.pkeppeler.deepcharter.test.support.BandCost.Window;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ColumnCost;
import io.github.pkeppeler.deepcharter.test.support.ColumnCost.Measurement;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures what the campaign generator (#742) costs a new column, per band (ADR 0086, which amends ADR 0029's bar). Each band's window
 * is the shipped campaign world cut to that band's rows ({@code campaign_surface}, {@code campaign_layer_1} and so on),
 * and its floor is the window cut to its bottom 16 rows ({@code campaign_surface_floor} and so on). A band's marginal CPU per filled
 * row is its window's pooled median over its floor's, per row of the difference; {@code tall_layer} (one layer's rock and fill in every
 * row) over {@code tall_layer_floor} is the reference. The gate: every band at most {@value BandCost#BAR}x the reference, except the
 * bands {@link #RATCHETS} holds to their own measured ratio plus 15% until their issue. A band is over when the lower bound of its
 * runs' own ratios is over its bar ({@link BandCost#lowerBound}), not when the pooled ratio is. The whole world, {@code campaign_full} per
 * block of height against {@code tall_baseline} (vanilla stone, no fill), is printed as a tracked number and no longer gates.
 *
 * <p>{@code everyCostWorldGeneratesColumns} generates a few columns of each world and checks they exist. With the number of rounds in
 * the environment, for example {@code DEEPCHARTER_CAMPAIGN_COST=6 tools/gametest.sh 'campaign_cost_test_each_band*'}, the measured run
 * takes that many readings of each world, alternating which is first, and prints them (CPU, heap, wall, and garbage
 * collection time and count, per round) as {@code [campaign-cost]} lines; a run needs at least {@value #MIN_ROUNDS}
 * rounds. {@value #POOL_ENV}=<absolute file> pools runs: each run appends its readings to the file and reads every
 * run's back, and the gate judges the pool once it is full ({@link BandCost.Pool#judged}). A pool short of that prints
 * the ratios and asserts nothing. {@value #ONLY_ENV}=<world> (or a comma-separated list) measures only those worlds.
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

    private static final String CAMPAIGN = "campaign_full";
    /** Vanilla stone, no zone fill: the whole world's tracked ratio. */
    private static final String BASELINE = "tall_baseline";
    /** One layer's own rock with the fill in every row: the per-row cost a band is held to. */
    private static final String REFERENCE = "tall_layer";

    private static final String REFERENCE_FLOOR = "tall_layer_floor";

    private static final int FLOOR_ROWS = 16;

    /** Every band but unbuilt depth, which is no band of content. */
    private static final List<Window> WINDOWS = List.of(
            new Window(CampaignBands.SURFACE, "campaign_surface", "campaign_surface_floor"),
            new Window(CampaignBands.LAYER_1, "campaign_layer_1", "campaign_layer_1_floor"),
            new Window(CampaignBands.LAYER_2, "campaign_layer_2", "campaign_layer_2_floor"),
            new Window(CampaignBands.LAYER_3, "campaign_layer_3", "campaign_layer_3_floor"));

    /**
     * The bands still over the bar (ADR 0086), each at its measured ratio. Layer 1 had one until #1160 (ADR 0090) took it under.
     * No other band may have one ({@link #RATCHETED}).
     */
    private static final List<Ratchet> RATCHETS = List.of(new Ratchet(CampaignBands.LAYER_2, 1.597, 1132));

    /** The only bands a ratchet may hold. */
    private static final Set<CampaignBands.Band> RATCHETED = Set.of(CampaignBands.LAYER_2);

    /** The worlds the gate reads. */
    private static final List<String> GATED = Stream.concat(
                    WINDOWS.stream().flatMap(window -> Stream.of(window.world(), window.floor())),
                    Stream.of(REFERENCE, REFERENCE_FLOOR))
            .toList();

    private static final List<String> ALL_WORLDS =
            Stream.concat(Stream.of(BASELINE, CAMPAIGN), GATED.stream()).toList();
    /** The worlds whose zone fill has rock to work on. */
    private static final List<String> FILLED_WORLDS =
            List.of(REFERENCE, CAMPAIGN, "campaign_layer_1", "campaign_layer_2", "campaign_layer_3");

    private static final List<String> WORLDS =
            REQUESTED_WORLD == null ? ALL_WORLDS : List.of(REQUESTED_WORLD.split(","));
    /** A chunk of each world's own, away from the origin that the measured columns centre on. */
    private static final int CHUNK = 600;
    /** The chunks whose rows each window must share with the campaign world, besides one that holds a site of its layer. */
    private static final List<ChunkPos> SAME_ROWS_CHUNKS =
            List.of(new ChunkPos(700, 700), new ChunkPos(703, -697), new ChunkPos(-706, 709));
    /** Chunks to each side searched for a site of a layer: a structure spacing (384 blocks) is 24. */
    private static final int SITE_SEARCH_CHUNKS = 24;

    private static final int MIN_ROUNDS = 4;
    private static final int MAX_TICKS = 200_000;
    /**
     * Four hours: each tick a measured run waits on sleeps {@link FarChunks#POLL_MILLIS}, and one run takes about 7 minutes, a judged
     * pool of {@value BandCost#RUNS} about 20. Only the measured run, which {@value #ROUNDS_ENV} opts into and CI never sets, has it;
     * the smoke case keeps {@link #MAX_TICKS}.
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
     * A window's rows are the campaign world's, so it costs what its band does there: in each of {@link #SAME_ROWS_CHUNKS} and, for a
     * layer with structures, a chunk that holds a site of that layer, every block state and every biome cell of every row of the window, both band
     * edges among them, are the campaign world's. It loads the chunks on the server thread, so it has a batch of its own.
     */
    @GameTest(environment = CampaignEnvironments.SYNC_CHUNK_LOAD, maxTicks = MAX_TICKS)
    public void eachWindowHoldsTheCampaignWorldsRows(GameTestHelper helper) {
        ServerLevel campaign = world(helper, CAMPAIGN);
        BlockPos conduit = Colony.plannedAnchor(helper.getLevel().getServer(), ColonyAnchor.CONDUIT)
                .orElseThrow(() -> failure(helper, "the colony's pad is not chosen, so no site can be found"));
        for (Window window : WINDOWS) {
            ServerLevel level = world(helper, window.world());
            Set<ChunkPos> chunks = new LinkedHashSet<>(SAME_ROWS_CHUNKS);
            LayerChain.campaignLayersIn(level).stream()
                    .filter(band -> !StructureKind.inLayer(band.layer()).isEmpty())
                    .forEach(band -> chunks.add(siteChunk(helper, campaign, band, conduit)));
            for (ChunkPos chunk : chunks) {
                expectSameRows(helper, campaign.getChunk(chunk.x(), chunk.z()), level, window.world());
            }
        }
        helper.succeed();
    }

    private static void expectSameRows(GameTestHelper helper, LevelChunk whole, ServerLevel level, String world) {
        LevelChunk cut = level.getChunk(whole.getPos().x(), whole.getPos().z());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int blocks = 0;
        int biomes = 0;
        // level-height: a window is a test world cut to a band's rows, and every row it has is compared
        for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    pos.set(whole.getPos().getBlockX(x), y, whole.getPos().getBlockZ(z));
                    if (!whole.getBlockState(pos).equals(cut.getBlockState(pos))
                            && !grainedByThePassOnly(whole, cut, pos)) {
                        blocks++;
                    }
                    if ((x & 3) == 0 && (y & 3) == 0 && (z & 3) == 0) {
                        int qx = QuartPos.fromBlock(pos.getX());
                        int qy = QuartPos.fromBlock(y);
                        int qz = QuartPos.fromBlock(pos.getZ());
                        if (!whole.getNoiseBiome(qx, qy, qz)
                                .unwrapKey()
                                .equals(cut.getNoiseBiome(qx, qy, qz).unwrapKey())) {
                            biomes++;
                        }
                    }
                }
            }
        }
        expect(
                helper,
                blocks == 0 && biomes == 0,
                "%s blocks and %s biome cells of %s differ from %s's in chunk %s",
                blocks,
                biomes,
                world,
                CAMPAIGN,
                whole.getPos());
    }

    /**
     * A row of the campaign world's grained crust where the window holds the breach crust. The crust pass (#216) is a
     * feature of the unbuilt-depth biome, which a window cut to a band's rows does not hold, so a floor that the seed
     * draws grained is the breach crust there, and the pass's few hundred block writes are not in the window's cost
     * (#1022).
     */
    private static boolean grainedByThePassOnly(LevelChunk whole, LevelChunk cut, BlockPos pos) {
        return whole.getBlockState(pos).is(LayerBlocks.GRAINED_CRUST)
                && cut.getBlockState(pos).is(LayerBlocks.BREACH_CRUST);
    }

    /** A chunk near {@link #SAME_ROWS_CHUNKS}' first that a site of {@code band}'s layer reaches into, by the sites the draw uses. */
    private static ChunkPos siteChunk(
            GameTestHelper helper, ServerLevel campaign, LayerChain.Band band, BlockPos conduit) {
        ChunkPos origin = SAME_ROWS_CHUNKS.getFirst();
        for (int dx = 0; dx < SITE_SEARCH_CHUNKS; dx++) {
            for (int dz = 0; dz < SITE_SEARCH_CHUNKS; dz++) {
                ChunkPos chunk = new ChunkPos(origin.x() + dx, origin.z() + dz);
                List<StructureSite> sites = LayerStructures.sitesIn(
                        campaign.getSeed(), band.layer(), band.minY(), band.height(), chunk, conduit);
                if (sites.stream()
                        .anyMatch(site -> site.bounds()
                                .intersects(
                                        chunk.getMinBlockX(),
                                        chunk.getMinBlockZ(),
                                        chunk.getMaxBlockX(),
                                        chunk.getMaxBlockZ()))) {
                    return chunk;
                }
            }
        }
        throw failure(helper, "no site of layer %s within %s chunks of %s", band.layer(), SITE_SEARCH_CHUNKS, origin);
    }

    /**
     * A window draws only its own band's structures (ADR 0086): the Prospector wreck is layer 2's, so the layer 1 window has none and
     * the layer 2 window has the campaign world's. Without the filter in {@code LayerChain.campaignLayersIn} the layer 1 window would
     * draw layer 2's sites outside its rows.
     */
    @GameTest
    public void aWindowDrawsOnlyItsOwnBandsStructures(GameTestHelper helper) {
        expect(
                helper,
                StructureKind.WRECK.layer() == 2,
                "the wreck is in layer %s, not 2",
                StructureKind.WRECK.layer());
        Optional<StructureSite> whole = LayerStructures.prospector(world(helper, CAMPAIGN));
        expect(helper, whole.isPresent(), "%s has no Prospector wreck", CAMPAIGN);
        Optional<StructureSite> layer2 = LayerStructures.prospector(world(helper, "campaign_layer_2"));
        expect(helper, layer2.equals(whole), "campaign_layer_2's wreck %s, expected %s's %s", layer2, CAMPAIGN, whole);
        Optional<StructureSite> layer1 = LayerStructures.prospector(world(helper, "campaign_layer_1"));
        expect(helper, layer1.isEmpty(), "campaign_layer_1 has layer 2's wreck %s", layer1);
        helper.succeed();
    }

    /** The windows and the reference put ore in a fresh column, so a band's rows are compared with a fill's (#743). */
    @GameTest(environment = CampaignEnvironments.SYNC_CHUNK_LOAD, maxTicks = MAX_TICKS)
    public void theCostWorldsRunTheZoneFill(GameTestHelper helper) {
        for (String world : FILLED_WORLDS) {
            ServerLevel level = world(helper, world);
            // Not the column of the colony or of another test's cell: the middle of the sample, as ColumnCost
            // samples it.
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
     * A layer's window ends far below the pad's ground, where the campaign world's Conduit tops out, so the casing stops at the window's
     * top row instead of writing past it when its chunk loads. Without the clamp the write throws in the load callback and the chunk never
     * loads, so the wait fails.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS)
    public void theConduitStopsAtALayerWindowsTop(GameTestHelper helper) {
        BlockPos conduit = Colony.plannedAnchor(helper.getLevel().getServer(), ColonyAnchor.CONDUIT)
                .orElseThrow(() -> failure(helper, "the colony's pad is not chosen, so it has no Conduit"));
        ServerLevel level = world(helper, "campaign_layer_1");
        // level-height: the top row of a window, a test world cut to a band's rows
        BlockPos top = new BlockPos(conduit.getX(), level.getMaxY(), conduit.getZ());
        FarChunks.whenBlocksLoaded(
                helper, List.of(new FarChunks.At(level, conduit.getX() >> 4, conduit.getZ() >> 4)), () -> {
                    expect(
                            helper,
                            level.getBlockState(top).is(ColonyBlocks.CONDUIT),
                            "no Conduit casing at campaign_layer_1's top, %s",
                            top);
                    helper.succeed();
                });
    }

    /**
     * Each window is the shipped campaign world, generator and type, with only the type's height cut to its band's rows (from the band
     * table), and every band of content has one; each floor is its window cut to its bottom {@value #FLOOR_ROWS} rows. A layer's window
     * has no skylight, as the reference has none: in the campaign world a layer lies under the surface's ground, and a sky over the
     * window would light rows the campaign world keeps dark. Only {@link #RATCHETED} bands have a ratchet.
     */
    @GameTest
    public void theWindowsAreTheCampaignWorldCutToTheirBands(GameTestHelper helper) throws IOException {
        List<CampaignBands.Band> bands = CampaignBands.ALL.stream()
                .filter(band -> band != CampaignBands.UNBUILT)
                .toList();
        List<CampaignBands.Band> windowed = WINDOWS.stream().map(Window::band).toList();
        expect(helper, windowed.equals(bands), "windows for %s, expected one for each of %s", windowed, bands);
        for (Ratchet ratchet : RATCHETS) {
            expect(
                    helper,
                    RATCHETED.contains(ratchet.band()),
                    "a ratchet for %s: only %s may have one, and a new band is held to the bar",
                    ratchet.band().name(),
                    RATCHETED);
        }
        JsonObject dimensions = read(helper, "/data/minecraft/worldgen/world_preset/flat_all_dimensions.json")
                .getAsJsonObject()
                .getAsJsonObject("dimensions");
        JsonObject shipped =
                read(helper, "/data/minecraft/dimension/overworld.json").getAsJsonObject();
        JsonElement campaign = shipped.get("generator");
        JsonObject campaignType =
                read(helper, "/data/minecraft/dimension_type/overworld.json").getAsJsonObject();
        for (Window window : WINDOWS) {
            CampaignBands.Band band = window.band();
            boolean sky = band == CampaignBands.SURFACE;
            expectFromShipped(helper, dimensions, window.world(), shipped);
            expectFromShipped(helper, dimensions, window.floor(), shipped);
            expectCut(helper, dimensions, window.world(), campaign, cut(campaignType, band.minY(), band.height(), sky));
            expectCut(helper, dimensions, window.floor(), campaign, cut(campaignType, band.minY(), FLOOR_ROWS, sky));
        }
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

    // A stray key or a stale hand copy must not pass.
    private static void expectFromShipped(
            GameTestHelper helper, JsonObject dimensions, String world, JsonObject shipped) {
        JsonObject expected = shipped.deepCopy();
        expected.addProperty("type", "deepcharter:" + world);
        expect(
                helper,
                expected.equals(dimensions.get("deepcharter:" + world)),
                "%s is not the shipped overworld with its own type: %s",
                world,
                dimensions.get("deepcharter:" + world));
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
        String tree = tree();
        LOGGER.info(
                "[campaign-cost] {} columns per world and round, run {} of tree {}; gate (ADR 0086): each band's CPU per filled"
                        + " row at most {}x {}'s, or its ratchet",
                ColumnCost.measuredColumns(),
                RUN,
                tree,
                BandCost.BAR,
                REFERENCE);
        List<Reading> readings = new ArrayList<>();
        results.forEach((world, all) -> {
            LOGGER.info(
                    "[campaign-cost] {}: CPU ms per column, in round order: {}; heap MB: {}; wall ms per column: {}; GC ms per"
                            + " column: {}; GC count: {}; medians {} CPU, {} MB",
                    world,
                    all.stream().map(m -> ColumnCost.format(m.cpuMsPerColumn())).toList(),
                    all.stream().map(m -> Math.round(m.heapMb())).toList(),
                    all.stream()
                            .map(m -> ColumnCost.format(m.wallMsPerColumn()))
                            .toList(),
                    all.stream().map(m -> ColumnCost.format(m.gcMsPerColumn())).toList(),
                    all.stream().map(Measurement::gcCount).toList(),
                    ColumnCost.format(ColumnCost.median(all).cpuMsPerColumn()),
                    Math.round(ColumnCost.median(all).heapMb()));
            all.forEach(m -> readings.add(
                    new Reading(RUN, tree, ColumnCost.measuredColumns(), world, m.height(), m.cpuMsPerColumn())));
        });
        Pool pool = pool(helper, readings);
        for (String world : ALL_WORLDS) {
            if (pool.has(world)) {
                LOGGER.info(
                        "[campaign-cost] pooled {}: median {} CPU ms per column over {} rounds of {} runs, {} blocks tall",
                        world,
                        ColumnCost.format(pool.median(world)),
                        pool.rounds(world),
                        pool.runs(world).size(),
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
        List<Ratio> ratios = BandCost.ratios(pool, WINDOWS, RATCHETS, REFERENCE, REFERENCE_FLOOR);
        for (Ratio ratio : ratios) {
            LOGGER.info(
                    "[campaign-cost] band {}: {} CPU ms per column per row over {}, {}x {}'s pooled,"
                            + " the runs' own ratios at least {}x; at most {}x{} ({})",
                    ratio.window().band().name(),
                    String.format("%.4f", ratio.marginal()),
                    ratio.window().floor(),
                    String.format("%.3f", ratio.ratio()),
                    REFERENCE,
                    String.format("%.3f", ratio.bound()),
                    String.format("%.3f", ratio.bar()),
                    ratio.ratchet()
                            .map(held -> ", a ratchet until #" + held.issue())
                            .orElse(""),
                    ratio.over() ? "over" : "within");
        }
        if (!pool.judged(GATED)) {
            LOGGER.info(
                    "[campaign-cost] not judged: the gate needs {} runs with as many rounds each and {} in all, of every world,"
                            + " pooled through {}",
                    BandCost.RUNS,
                    BandCost.ROUNDS,
                    POOL_ENV);
            return;
        }
        List<Ratio> over = ratios.stream().filter(Ratio::over).toList();
        LOGGER.info("[campaign-cost] judged: {}", over.isEmpty() ? "every band within its bar" : "over: " + over);
        expect(helper, over.isEmpty(), "bands over their bar against %s: %s", REFERENCE, over);
    }

    /**
     * This run's readings, with every earlier run's in the pool file when there is one. The pool is checked with this run's readings in
     * it before they are written, so a run of another tree or round size never spoils the file.
     */
    private static Pool pool(GameTestHelper helper, List<Reading> readings) {
        if (REQUESTED_POOL == null) {
            return new Pool(readings);
        }
        Path file = Path.of(REQUESTED_POOL);
        try {
            List<Reading> all = new ArrayList<>(Pool.read(file).readings());
            all.addAll(readings);
            Pool pool = new Pool(all);
            Pool.append(file, readings);
            return pool;
        } catch (IOException e) {
            throw failure(helper, "cannot pool the readings through %s: %s", file, e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw failure(
                    helper,
                    "this run does not pool with %s: %s; delete it and start the pool again",
                    file,
                    e.getMessage());
        }
    }

    /** The commit measured, {@code -dirty} when the tree has changes, so a pool never mixes trees; {@code unknown} without git. */
    private static String tree() {
        try {
            Process git = new ProcessBuilder("git", "describe", "--always", "--dirty", "--abbrev=40")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return git.waitFor() == 0 && !out.isEmpty() ? out : "unknown";
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
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
