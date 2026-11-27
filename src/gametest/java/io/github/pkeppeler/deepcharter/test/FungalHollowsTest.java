package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.layer.gen.AirFaceLiningFeature;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.surface.GiantFungusFeature;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for layer 3, the Fungal Hollows (#972), a band of the campaign world: its caverns are mostly air, columns line every face
 * of its rock that is open to them and only those (#504), its zones are the biomes of the {@code deepcharter:fungal_hollows} tag, its
 * glowcaps grow on floors, and its zone fill puts the most gas in the Spore Fields. The band's edges, crust and zone biomes are
 * {@link CampaignTerrainTest}'s, which judges every layer of the band table.
 */
public class FungalHollowsTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(FungalHollowsTest.class);
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 6000;
    private static final int LAYER = 3;
    /** The low corner chunk of the sample, a square of {@link #SAMPLE_CHUNKS} chunks a side: no other campaign test generates it. */
    private static final int SAMPLE_CHUNK = 24;

    private static final int SAMPLE_CHUNKS = 2;
    /**
     * The glowcaps are counted over this many chunks a side from {@link #SAMPLE_CHUNK}: a patch holds up to 12, so over the sample's 2 the
     * Garden led the Spore Fields by one glowcap.
     */
    private static final int GLOWCAP_CHUNKS = 10;
    /** How many times each other zone's glowcaps the Garden grows at least. */
    private static final double GARDEN_LEAD = 1.5;

    private static final TagKey<Biome> FUNGAL_HOLLOWS =
            TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "fungal_hollows"));
    /** The share of the band's cells that must be open: huge caverns, mostly air (SPEC section 3). */
    private static final double MOSTLY_AIR = 0.5;

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theCavernsAreMostlyAir(GameTestHelper helper) {
        whenSampleLoaded(helper, () -> {
            ServerLevel level = TestLayers.level(helper, LAYER);
            LayerChain.Band band = TestLayers.band(level, LAYER);
            long[] open = new long[Zones.COUNT];
            long[] cells = new long[Zones.COUNT];
            for (BlockPos column : columns()) {
                for (int y = band.minY(); y < band.minY() + band.height(); y++) {
                    int zone = Zones.index(band.minY(), band.height(), y);
                    cells[zone]++;
                    if (AirFaceLiningFeature.isOpen(state(level, column.atY(y)))) {
                        open[zone]++;
                    }
                }
            }
            long allOpen = 0;
            long all = 0;
            for (int zone = 0; zone < Zones.COUNT; zone++) {
                LOGGER.info("[fungal-hollows] zone {}: {} of {} cells open", zone, open[zone], cells[zone]);
                allOpen += open[zone];
                all += cells[zone];
                expect(helper, open[zone] > 0, "zone %s of layer 3 has no cavern", zone);
            }
            double share = (double) allOpen / all;
            expect(helper, share > MOSTLY_AIR, "layer 3 is %.2f open, not mostly air", share);
            helper.succeed();
        });
    }

    /**
     * Every column stands within the lining's depth of an open cell, straight along an axis, and every block of the core does not (#504): both
     * sides of the knob, so a lining one block too deep or too shallow fails. No lava stands within
     * {@link AirFaceLiningFeature#SEAL_MARGIN} blocks more than that either: the lining seals it, so its light stays in the rock and
     * never shines through a column.
     */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void onlyTheFacesOpenToTheCavernsAreLined(GameTestHelper helper) {
        whenSampleLoaded(helper, () -> {
            ServerLevel level = TestLayers.level(helper, LAYER);
            LayerChain.Band band = TestLayers.band(level, LAYER);
            int depth = lining().get("depth").getAsInt();
            int columns = 0;
            int cores = 0;
            for (BlockPos column : columns()) {
                for (int y = band.minY(); y < band.minY() + band.height(); y++) {
                    BlockPos pos = column.atY(y);
                    BlockState state = state(level, pos);
                    if (state.is(LayerBlocks.COLUMNAR_BASALT)) {
                        columns++;
                        expect(
                                helper,
                                nearOpen(level, pos, depth),
                                "a column at %s faces no cavern",
                                pos.toShortString());
                    } else if (state.is(Blocks.LAVA)) {
                        expect(
                                helper,
                                !nearOpen(level, pos, depth + AirFaceLiningFeature.SEAL_MARGIN),
                                "the lava at %s lights a cavern",
                                pos.toShortString());
                    } else if (state.is(LayerBlocks.BASALT_CORE)) {
                        cores++;
                        expect(
                                helper,
                                !nearOpen(level, pos, depth),
                                "the core at %s faces a cavern within %s blocks and is not lined",
                                pos.toShortString(),
                                depth);
                    }
                }
            }
            LOGGER.info("[fungal-hollows] {} columns and {} core blocks in the sample", columns, cores);
            expect(helper, columns > 0 && cores > 0, "the sample has %s columns and %s core blocks", columns, cores);
            helper.succeed();
        });
    }

    /**
     * Lining a chunk reads its neighbours a chunk at a time, never block by block through the level (#972): reading every rock block's
     * neighbours one at a time made the lining the most costly part of layer 3 to generate. The sample is lined already, so lining a chunk
     * of it again changes nothing.
     */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theLiningReadsItsNeighboursAChunkAtATime(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, LAYER);
        AirFaceLiningFeature feature = AirFaceLiningFeature.CODEC
                .codec()
                .parse(JsonOps.INSTANCE, lining())
                .getOrThrow();
        int[] blockReads = {0};
        WorldGenLevel counting = (WorldGenLevel) Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBlockState")) {
                        blockReads[0]++;
                    }
                    try {
                        return method.invoke(level, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        BlockPos origin = new BlockPos((SAMPLE_CHUNK + 1) << 4, 0, (SAMPLE_CHUNK + 1) << 4);
        boolean changed = feature.place(counting, level.getChunkSource().getGenerator(), level.getRandom(), origin);
        expect(helper, !changed, "lining a chunk that is lined already changed it");
        expect(
                helper,
                blockReads[0] == 0,
                "lining one chunk read %s blocks one at a time through the level",
                blockReads[0]);
        helper.succeed();
    }

    /**
     * The biome tag the creatures of layer 3 key on (#918, #919) holds every zone of the layer and no other shipped biome. The test data
     * pack adds its own test biome to the tag (the Drifters' arenas, #973), which is left out.
     */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theZonesAreTheFungalHollowsBiomes(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, LAYER);
        LayerChain.Band band = TestLayers.band(level, LAYER);
        Set<String> zones = new TreeSet<>();
        for (int zone = 0; zone < Zones.COUNT; zone++) {
            zones.add(zoneId(band, zone).toString());
        }
        Set<String> tagged = new TreeSet<>();
        for (var biome : level.registryAccess().lookupOrThrow(Registries.BIOME).getTagOrEmpty(FUNGAL_HOLLOWS)) {
            Identifier id = biome.unwrapKey().orElseThrow().identifier();
            if (id.getNamespace().equals(DeepCharter.MOD_ID)) {
                tagged.add(id.toString());
            }
        }
        expect(helper, tagged.equals(zones), "the fungal_hollows tag holds %s, the zones are %s", tagged, zones);
        helper.succeed();
    }

    /**
     * The lining is read once before it writes (see {@link AirFaceLiningFeature}), which is right only while nothing it writes or keeps is
     * open: the lining, the rock and every seal of each lining feature stay out of {@link AirFaceLiningFeature#OPEN}.
     */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theLiningNeverWritesAnOpenBlock(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, LAYER);
        int linings = 0;
        for (var feature : level.registryAccess().lookupOrThrow(Registries.FEATURE)) {
            if (!(feature instanceof AirFaceLiningFeature lining)) {
                continue;
            }
            linings++;
            List<Block> written = new ArrayList<>(lining.seals());
            written.add(lining.rock());
            written.add(lining.lining());
            for (Block block : written) {
                expect(
                        helper,
                        !AirFaceLiningFeature.isOpen(block.defaultBlockState()),
                        "%s is open to the lining (air, or in %s), so lining reads stale cells",
                        BuiltInRegistries.BLOCK.getKey(block),
                        AirFaceLiningFeature.OPEN.location());
            }
        }
        expect(helper, linings > 0, "no air_face_lining feature is registered");
        helper.succeed();
    }

    /**
     * Glowcaps grow in the sample, each where it can stand (a floor, or a fungal stalk's top), and the Garden grows the most of them, by a
     * margin: at least
     * {@link #GARDEN_LEAD} times as many as each other zone, over {@link #GLOWCAP_CHUNKS} chunks a side. Over 576 chunks the zones grow
     * about 2.0 and 3.0 times fewer than the Garden.
     */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void glowcapsGrowOnTheFloorsMostOfAllInTheGarden(GameTestHelper helper) {
        whenGlowcapSampleLoaded(helper, () -> {
            ServerLevel level = TestLayers.level(helper, LAYER);
            LayerChain.Band band = TestLayers.band(level, LAYER);
            int[] found = new int[Zones.COUNT];
            for (BlockPos column : grid(GLOWCAP_CHUNKS, 1)) {
                for (int y = band.minY(); y < band.minY() + band.height(); y++) {
                    BlockPos pos = column.atY(y);
                    if (state(level, pos).is(LayerBlocks.GLOWCAP)) {
                        found[Zones.index(band.minY(), band.height(), y)]++;
                        expect(
                                helper,
                                state(level, pos).canSurvive(level, pos),
                                "the glowcap at %s cannot stand on %s",
                                pos.toShortString(),
                                state(level, pos.below()));
                    }
                }
            }
            LOGGER.info("[fungal-hollows] glowcaps by zone, top first: {} {} {}", found[0], found[1], found[2]);
            expect(
                    helper,
                    found[0] >= GARDEN_LEAD * found[1] && found[0] >= GARDEN_LEAD * found[2],
                    "the Garden grows %s glowcaps, the Spore Fields %s and the Quiet %s: not " + GARDEN_LEAD
                            + " times each other zone's",
                    found[0],
                    found[1],
                    found[2]);
            helper.succeed();
        });
    }

    /**
     * Every position a feature of layer 3 picks lies in the chunk it is placed for. A glowcap or stalk patch spread across a chunk's side
     * would land where the neighbour grows its own, so which one stands would depend on the order chunks are decorated in (#972). It runs
     * each placed feature of the zone biomes' placement, as the generator does, from each chunk of the sample, and checks the positions
     * of every step: a later filter reads the world as it is now, after the sample grew, so it would drop most of them.
     */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void everyFeaturePlacesInsideItsOwnChunk(GameTestHelper helper) {
        whenSampleLoaded(helper, () -> {
            ServerLevel level = TestLayers.level(helper, LAYER);
            Set<PlacedFeature> features = new LinkedHashSet<>();
            level.registryAccess()
                    .lookupOrThrow(Registries.BIOME)
                    .getTagOrEmpty(FUNGAL_HOLLOWS)
                    .forEach(biome -> biome.value()
                            .getGenerationSettings()
                            .features()
                            .forEach(step -> step.stream().map(Holder::value).forEach(features::add)));
            expect(helper, !features.isEmpty(), "the zone biomes have no features");
            int positions = 0;
            for (int dx = 0; dx < SAMPLE_CHUNKS; dx++) {
                for (int dz = 0; dz < SAMPLE_CHUNKS; dz++) {
                    ChunkPos chunk = new ChunkPos(SAMPLE_CHUNK + dx, SAMPLE_CHUNK + dz);
                    for (PlacedFeature feature : features) {
                        PlacementContext context = new PlacementContext(
                                level, level.getChunkSource().getGenerator(), Optional.of(feature));
                        RandomSource random = RandomSource.create(chunk.pack());
                        List<BlockPos> picked = List.of(chunk.getWorldPosition());
                        for (PlacementModifier modifier : feature.placement()) {
                            List<BlockPos> next = new ArrayList<>();
                            for (BlockPos pos : picked) {
                                modifier.modify(context, random, pos, next::add);
                            }
                            for (BlockPos pos : next) {
                                expect(
                                        helper,
                                        ChunkPos.containing(pos).equals(chunk),
                                        "%s placed for chunk %s picks %s, in chunk %s",
                                        feature.feature().getRegisteredName(),
                                        chunk,
                                        pos.toShortString(),
                                        ChunkPos.containing(pos));
                            }
                            positions += next.size();
                            picked = next;
                        }
                    }
                }
            }
            expect(helper, positions > 0, "no feature of layer 3 picked a position");
            helper.succeed();
        });
    }

    /**
     * Each zone grows the giant fungi its mix names (#1202), over {@link #GLOWCAP_CHUNKS} chunks a side: the Garden all
     * three forms, the Spore Fields spires, and the Quiet parasols and never a great stalk. A form is told by the
     * blocks it is made of.
     */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void eachZoneGrowsTheGiantsOfItsMix(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, LAYER);
        LayerChain.Band band = TestLayers.band(level, LAYER);
        List<Map<String, Integer>> found = new ArrayList<>();
        for (int zone = 0; zone < Zones.COUNT; zone++) {
            found.add(new TreeMap<>());
        }
        for (BlockPos column : grid(GLOWCAP_CHUNKS, 1)) {
            for (int y = band.minY(); y < band.minY() + band.height(); y++) {
                BlockPos pos = column.atY(y);
                String form = giantForm(state(level, pos));
                if (form != null) {
                    found.get(Zones.index(band.minY(), band.height(), y)).merge(form, 1, Integer::sum);
                }
            }
        }
        LOGGER.info("[fungal-hollows] giant blocks by zone, top first: {}", found);
        expect(
                helper,
                found.get(0).keySet().equals(Set.of("great_stalk", "parasol", "spires")),
                "the Garden grows %s",
                found.get(0));
        expect(helper, found.get(1).containsKey("spires"), "the Spore Fields grow %s", found.get(1));
        expect(helper, found.get(2).containsKey("parasol"), "the Quiet grows %s", found.get(2));
        expect(helper, !found.get(2).containsKey("great_stalk"), "the Quiet grows %s", found.get(2));
        helper.succeed();
    }

    /** The form a giant fungus block belongs to, or null for any other block. */
    private static @Nullable String giantForm(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (path.startsWith("great_stalk")) {
            return "great_stalk";
        }
        if (path.startsWith("parasol_")) {
            return "parasol";
        }
        if (path.startsWith("spire")) {
            return "spires";
        }
        return null;
    }

    /**
     * The giant fungus features are placed in the zones of the Fungal Hollows and in no other biome: one for each zone,
     * every one listed last in step 8 of every zone, so none moves a feature already there (#1202).
     */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void giantFungiGrowOnlyInTheFungalHollows(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, LAYER);
        Set<String> placed = new TreeSet<>();
        for (var biome : level.registryAccess()
                .lookupOrThrow(Registries.BIOME)
                .listElements()
                .toList()) {
            for (var step : biome.value().getGenerationSettings().features()) {
                for (Holder<PlacedFeature> feature : step) {
                    if (feature.value().feature().value() instanceof GiantFungusFeature) {
                        placed.add(feature.getRegisteredName());
                        expect(
                                helper,
                                biome.is(FUNGAL_HOLLOWS),
                                "%s grows %s outside the Fungal Hollows",
                                biome.getRegisteredName(),
                                feature.getRegisteredName());
                    }
                }
            }
        }
        expect(helper, placed.size() == Zones.COUNT, "the giant fungus features are %s, not one a zone", placed);
        helper.succeed();
    }

    /**
     * A giant fungus writes only inside the chunk it grows for (ADR 0088), whatever form it takes and wherever in the
     * chunk its site is: each zone's feature runs on each chunk of the sample through a level that records every block
     * it sets and keeps none, with ten seeds of its random, so the site moves about the chunk.
     */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void everyGiantFungusWritesInsideItsOwnChunk(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, LAYER);
        List<BlockPos> written = new ArrayList<>();
        WorldGenLevel recording = (WorldGenLevel) Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setBlock")) {
                        written.add(((BlockPos) args[0]).immutable());
                        return true;
                    }
                    try {
                        return method.invoke(level, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        int grown = 0;
        Map<Integer, Integer> byZone = new TreeMap<>();
        for (var feature : level.registryAccess().lookupOrThrow(Registries.FEATURE)) {
            if (!(feature instanceof GiantFungusFeature giant)) {
                continue;
            }
            for (int dx = 0; dx < GLOWCAP_CHUNKS; dx++) {
                for (int dz = 0; dz < GLOWCAP_CHUNKS; dz++) {
                    ChunkPos chunk = new ChunkPos(SAMPLE_CHUNK + dx, SAMPLE_CHUNK + dz);
                    for (long seed = 0; seed < 10; seed++) {
                        written.clear();
                        if (giant.place(
                                recording,
                                level.getChunkSource().getGenerator(),
                                RandomSource.create(seed),
                                chunk.getWorldPosition())) {
                            grown++;
                            byZone.merge(giant.zone(), 1, Integer::sum);
                        }
                        for (BlockPos pos : written) {
                            expect(
                                    helper,
                                    ChunkPos.containing(pos).equals(chunk),
                                    "a giant fungus grown for chunk %s set %s, in chunk %s",
                                    chunk,
                                    pos.toShortString(),
                                    ChunkPos.containing(pos));
                        }
                    }
                }
            }
        }
        LOGGER.info("[fungal-hollows] {} giant fungi grown through the recording level, by zone {}", grown, byZone);
        expect(helper, grown > 0, "no giant fungus grew");
        helper.succeed();
    }

    /**
     * Each zone's gas pockets are its fill's table, within five standard deviations, and the Spore Fields' table holds the most gas of the
     * layer: spore gas is its hazard (SPEC section 3). A candidate is a block the fill decided: the rock (core or column, which the lining
     * made of core after the fill), an ore, Company rock, gas or a lava source.
     */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theSporeFieldsHoldTheMostGas(GameTestHelper helper) {
        whenSampleLoaded(helper, () -> {
            ServerLevel level = TestLayers.level(helper, LAYER);
            LayerChain.Band band = TestLayers.band(level, LAYER);
            long[] candidates = new long[Zones.COUNT];
            long[] gas = new long[Zones.COUNT];
            for (BlockPos column : allColumns()) {
                // The crust rows at the floor are the material rule's, which the fill never sees.
                for (int y = band.minY() + LayerTuning.DEFAULT.crustThickness(); y < band.minY() + band.height(); y++) {
                    BlockState state = state(level, column.atY(y));
                    int zone = Zones.index(band.minY(), band.height(), y);
                    if (isCandidate(state)) {
                        candidates[zone]++;
                        if (state.is(HazardBlocks.GAS_POCKET)) {
                            gas[zone]++;
                        }
                    }
                }
            }
            double[] chances = new double[Zones.COUNT];
            for (int zone = 0; zone < Zones.COUNT; zone++) {
                JsonObject fill = fill(band, zone);
                double ores = 0;
                for (JsonElement ore : fill.getAsJsonArray("ores")) {
                    ores += ore.getAsJsonObject().get("chance").getAsDouble();
                }
                double chance = (1 - ores) * hazard(fill, "deepcharter:gas_pocket");
                chances[zone] = chance;
                double expected = candidates[zone] * chance;
                double tolerance = 5 * Math.sqrt(candidates[zone] * chance * (1 - chance)) + 1;
                LOGGER.info(
                        "[fungal-hollows] zone {}: {} gas pockets in {} candidates, expected {} +- {}",
                        zone,
                        gas[zone],
                        candidates[zone],
                        Math.round(expected),
                        Math.round(tolerance));
                expect(
                        helper,
                        Math.abs(gas[zone] - expected) <= tolerance,
                        "zone %s has %s gas pockets in %s candidates, expected %.0f +- %.0f",
                        zone,
                        gas[zone],
                        candidates[zone],
                        expected,
                        tolerance);
            }
            expect(
                    helper,
                    chances[1] > chances[0] && chances[1] > chances[2],
                    "the Spore Fields' gas %s is not the most of the layer: %s and %s",
                    chances[1],
                    chances[0],
                    chances[2]);
            helper.succeed();
        });
    }

    private static boolean isCandidate(BlockState state) {
        return state.is(LayerBlocks.BASALT_CORE)
                || state.is(LayerBlocks.COLUMNAR_BASALT)
                || OreRegistry.typeOf(state.getBlock()).isPresent()
                || state.is(HazardBlocks.COMPANY_ROCK)
                || state.is(HazardBlocks.GAS_POCKET)
                || (state.is(Blocks.LAVA) && state.getFluidState().isSource());
    }

    private static boolean nearOpen(ServerLevel level, BlockPos pos, int depth) {
        for (Direction direction : Direction.values()) {
            for (int step = 1; step <= depth; step++) {
                if (AirFaceLiningFeature.isOpen(state(level, pos.relative(direction, step)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One column in three each way of the sample: enough cells for the shape, few enough reads for one tick. */
    private static Iterable<BlockPos> columns() {
        return grid(SAMPLE_CHUNKS, 3);
    }

    private static Iterable<BlockPos> allColumns() {
        return grid(SAMPLE_CHUNKS, 1);
    }

    /** Every {@code step}th column each way of the square of {@code chunks} chunks a side from {@link #SAMPLE_CHUNK}. */
    private static Iterable<BlockPos> grid(int chunks, int step) {
        List<BlockPos> out = new ArrayList<>();
        int span = chunks * 16;
        for (int x = 0; x < span; x += step) {
            for (int z = 0; z < span; z += step) {
                out.add(new BlockPos((SAMPLE_CHUNK << 4) + x, 0, (SAMPLE_CHUNK << 4) + z));
            }
        }
        return out;
    }

    /**
     * The sample and a chunk of margin round it, which the lining reads across a chunk's edge, loaded before
     * {@code body} runs.
     */
    private static void whenSampleLoaded(GameTestHelper helper, Runnable body) {
        whenLoaded(helper, SAMPLE_CHUNKS, 1, body);
    }

    /** The {@link #GLOWCAP_CHUNKS} chunks the glowcaps are counted over, loaded before {@code body} runs. */
    private static void whenGlowcapSampleLoaded(GameTestHelper helper, Runnable body) {
        whenLoaded(helper, GLOWCAP_CHUNKS, 0, body);
    }

    private static void whenLoaded(GameTestHelper helper, int chunks, int margin, Runnable body) {
        FarChunks.whenBlocksLoaded(
                helper,
                FarChunks.rect(
                        TestLayers.level(helper, LAYER),
                        SAMPLE_CHUNK - margin,
                        SAMPLE_CHUNK - margin,
                        SAMPLE_CHUNK + chunks - 1 + margin,
                        SAMPLE_CHUNK + chunks - 1 + margin),
                body);
    }

    private static BlockState state(ServerLevel level, BlockPos pos) {
        // sync-load: every caller waited with whenSampleLoaded or whenGlowcapSampleLoaded
        return level.getChunk(pos).getBlockState(pos);
    }

    private static JsonObject lining() {
        return json("/data/deepcharter/worldgen/feature/line_hollows.json");
    }

    /** The fill of one zone of layer 3, from the same JSON the generator reads. */
    private static JsonObject fill(LayerChain.Band band, int zone) {
        return json(
                "/data/deepcharter/worldgen/feature/fill_" + zoneId(band, zone).getPath() + ".json");
    }

    private static Identifier zoneId(LayerChain.Band band, int zone) {
        Zones.Span span = Zones.span(band.minY(), band.height(), zone);
        return Zones.of(LAYER, band.minY(), band.height(), span.low())
                .orElseThrow()
                .id();
    }

    private static double hazard(JsonObject fill, String block) {
        for (JsonElement entry : fill.getAsJsonArray("hazards")) {
            if (entry.getAsJsonObject().get("block").getAsString().equals(block)) {
                return entry.getAsJsonObject().get("chance").getAsDouble();
            }
        }
        return 0;
    }

    private static JsonObject json(String resource) {
        var stream = FungalHollowsTest.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("no resource " + resource);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(resource, e);
        }
    }
}
