package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.test.support.CampaignBands;
import io.github.pkeppeler.deepcharter.test.support.CampaignBands.Band;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for the campaign world's terrain (#742, ADR 0059): the bands, the rock of each, the crust at the floor of each layer, and
 * the biome of each band, on our own generator. The world is {@code deepcharter:campaign_sample}, a test-preset dimension, as
 * {@code surface_sample} is for the surface; the shipped game does not use it yet. The band table is {@link CampaignBands}.
 */
public class CampaignTerrainTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = 6000;
    /** The chunks sampled: a square this many chunks on a side, away from the colony pad. */
    private static final int SAMPLE_CHUNK_X = 10;

    private static final int SAMPLE_CHUNK_Z = 10;
    private static final int SAMPLE_CHUNKS = 2;
    /** Every column of the sample in a grid this many blocks apart. */
    private static final int SAMPLE_STEP = 3;

    private static final String CRUST = "deepcharter:breach_crust";
    private static final String PACKED_SHALE = "deepcharter:packed_shale";
    private static final String GREEN_SHALE = "deepcharter:green_shale";
    /** The surface band's blocks: the material rule's, and what the surface's features (#245) place on the ground. */
    private static final Set<String> SURFACE_BLOCKS = Set.of(
            "deepcharter:regolith",
            "deepcharter:regolith_packed",
            "deepcharter:regolith_rock",
            "deepcharter:ochre_regolith",
            "deepcharter:basalt_outcrop",
            "deepcharter:salvage_crate",
            "deepcharter:fungal_stalk");
    /** The cave stalks of layer 1's first zone are the one thing besides rock that its biomes place. */
    private static final String STALK = "deepcharter:fungal_stalk";

    private static final Set<String> FLUIDS_AND_AIR = Set.of("minecraft:air", "minecraft:cave_air");

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theBandsAreWhereTheDecisionPutsThem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        expect(helper, CampaignBands.SURFACE.minY() == -64 && CampaignBands.SURFACE.maxY() == 319, "the surface band");
        expect(helper, CampaignBands.LAYER_1.minY() == -256 && CampaignBands.LAYER_1.maxY() == -65, "layer 1");
        expect(helper, CampaignBands.LAYER_2.minY() == -512 && CampaignBands.LAYER_2.maxY() == -257, "layer 2");
        expect(helper, CampaignBands.UNBUILT.minY() == -2032 && CampaignBands.UNBUILT.maxY() == -513, "unbuilt depth");
        for (int i = 0; i < CampaignBands.ALL.size(); i++) {
            Band band = CampaignBands.ALL.get(i);
            expect(helper, Math.floorMod(band.minY(), 16) == 0, "%s starts on a section edge", band.name());
            expect(helper, Math.floorMod(band.topExclusive(), 16) == 0, "%s ends on a section edge", band.name());
            if (i + 1 < CampaignBands.ALL.size()) {
                expect(
                        helper,
                        CampaignBands.ALL.get(i + 1).topExclusive() == band.minY(),
                        "%s has a gap or an overlap under it",
                        band.name());
            }
        }
        expect(
                helper,
                level.getMinY() == CampaignBands.worldMinY()
                        && level.getMaxY() + 1 == CampaignBands.worldTopExclusive(),
                "the level spans %d..%d, not the bands' %d..%d",
                level.getMinY(),
                level.getMaxY(),
                CampaignBands.worldMinY(),
                CampaignBands.worldTopExclusive() - 1);
        helper.succeed();
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theFloorOfLayers1And2HoldsThreeRowsOfCrust(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int rows = CampaignBands.crustRows();
        for (Band layer : CampaignBands.LAYERS) {
            for (BlockPos column : sampledColumns()) {
                for (int y = layer.minY(); y < layer.minY() + rows; y++) {
                    String block = idAt(level, column.atY(y));
                    expect(
                            helper,
                            block.equals(CRUST),
                            "%s: expected crust at %d %d %d, found %s",
                            layer.name(),
                            column.getX(),
                            y,
                            column.getZ(),
                            block);
                }
                String above = idAt(level, column.atY(layer.minY() + rows));
                expect(
                        helper,
                        !above.equals(CRUST),
                        "%s: more than %d rows of crust at %d %d",
                        layer.name(),
                        rows,
                        column.getX(),
                        column.getZ());
            }
        }
        helper.succeed();
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void crustIsOnlyAtTheFloorsSoNoneLiesUnderTheSurface(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Set<Integer> expected = new TreeSet<>();
        for (Band layer : CampaignBands.LAYERS) {
            for (int y = layer.minY(); y < layer.minY() + CampaignBands.crustRows(); y++) {
                expected.add(y);
            }
        }
        Set<Integer> found = new TreeSet<>();
        for (BlockPos column : sampledColumns()) {
            for (int y = CampaignBands.worldMinY(); y < CampaignBands.worldTopExclusive(); y++) {
                if (idAt(level, column.atY(y)).equals(CRUST)) {
                    found.add(y);
                }
            }
            // ADR 0011: the surface's floor is open, so layer 1 starts directly under it, with no crust between.
            for (int y = CampaignBands.LAYER_1.minY() + CampaignBands.crustRows();
                    y < CampaignBands.SURFACE.minY() + 4;
                    y++) {
                expect(
                        helper,
                        !idAt(level, column.atY(y)).equals(CRUST),
                        "crust under the surface at %d %d %d",
                        column.getX(),
                        y,
                        column.getZ());
            }
        }
        expect(helper, found.equals(expected), "crust is at %s, expected only %s", found, expected);
        helper.succeed();
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void eachBandHoldsItsOwnRock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Map<String, Set<String>> found = new TreeMap<>();
        for (BlockPos column : sampledColumns()) {
            for (int y = CampaignBands.worldMinY(); y < CampaignBands.worldTopExclusive(); y++) {
                String block = idAt(level, column.atY(y));
                if (block.equals("minecraft:air")) {
                    continue;
                }
                if (!isCrustRow(y)) {
                    found.computeIfAbsent(bandOf(y).name(), name -> new TreeSet<>())
                            .add(block);
                }
            }
        }
        Set<String> layer1 = Set.of(PACKED_SHALE, STALK);
        expectOnly(helper, found, CampaignBands.SURFACE, SURFACE_BLOCKS);
        expectOnly(helper, found, CampaignBands.LAYER_1, layer1);
        expectOnly(helper, found, CampaignBands.LAYER_2, Set.of(GREEN_SHALE));
        // Unbuilt depth is plain rock, the rock of the last layer.
        expectOnly(helper, found, CampaignBands.UNBUILT, Set.of(GREEN_SHALE));
        expect(
                helper,
                found.getOrDefault(CampaignBands.LAYER_1.name(), Set.of()).contains(PACKED_SHALE),
                "layer 1 has no packed shale");
        expect(
                helper,
                found.getOrDefault(CampaignBands.LAYER_2.name(), Set.of()).contains(GREEN_SHALE),
                "layer 2 has no green shale");
        expect(
                helper,
                found.getOrDefault(CampaignBands.SURFACE.name(), Set.of()).contains("deepcharter:regolith"),
                "the surface has no regolith");
        helper.succeed();
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void eachBandHasItsZoneBiome(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        BlockPos column = sampledColumns().getFirst();
        Set<String> surfaceBiomes = new TreeSet<>();
        read(helper, "/data/minecraft/dimension/overworld.json")
                .getAsJsonObject()
                .getAsJsonObject("generator")
                .getAsJsonObject("biome_source")
                .getAsJsonArray("biomes")
                .forEach(entry ->
                        surfaceBiomes.add(entry.getAsJsonObject().get("biome").getAsString()));
        for (int y : new int[] {CampaignBands.SURFACE.minY(), 64, CampaignBands.SURFACE.maxY()}) {
            expect(
                    helper,
                    surfaceBiomes.contains(biomeAt(level, column.atY(y))),
                    "the surface band at %d is %s, not one of %s",
                    y,
                    biomeAt(level, column.atY(y)),
                    surfaceBiomes);
        }
        for (int number = 1; number <= CampaignBands.LAYERS.size(); number++) {
            Band layer = CampaignBands.layer(number);
            for (int zone = 0; zone < Zones.COUNT; zone++) {
                Zones.Span span = Zones.span(layer.minY(), layer.height(), zone);
                // A cell is 4 blocks, judged by its middle, so a zone edge inside a cell is off by up to 2 blocks
                // (ZoneBiomeSource).
                for (int y : new int[] {span.low() + 2, (span.low() + span.high()) / 2, span.high() - 2}) {
                    String expected = Zones.of(number, layer.minY(), layer.height(), y)
                            .orElseThrow()
                            .id()
                            .toString();
                    expect(
                            helper,
                            biomeAt(level, column.atY(y)).equals(expected),
                            "layer %d zone %d at %d is %s, expected %s",
                            number,
                            zone,
                            y,
                            biomeAt(level, column.atY(y)),
                            expected);
                }
            }
        }
        for (int y : new int[] {CampaignBands.UNBUILT.minY(), -1000, CampaignBands.UNBUILT.maxY()}) {
            expect(
                    helper,
                    biomeAt(level, column.atY(y)).equals("deepcharter:unbuilt_depth"),
                    "unbuilt depth at %d is %s",
                    y,
                    biomeAt(level, column.atY(y)));
        }
        helper.succeed();
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void noVanillaBlockIsGenerated(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Set<String> vanilla = new TreeSet<>();
        for (int chunkX = SAMPLE_CHUNK_X; chunkX < SAMPLE_CHUNK_X + SAMPLE_CHUNKS; chunkX++) {
            for (int chunkZ = SAMPLE_CHUNK_Z; chunkZ < SAMPLE_CHUNK_Z + SAMPLE_CHUNKS; chunkZ++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
                for (var section : chunk.getSections()) {
                    section.maybeHas(state -> {
                        String id =
                                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        if (id.startsWith("minecraft:") && !FLUIDS_AND_AIR.contains(id)) {
                            vanilla.add(id);
                        }
                        return false;
                    });
                }
            }
        }
        expect(helper, vanilla.isEmpty(), "the campaign world holds vanilla blocks: %s", vanilla);
        helper.succeed();
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theDataMatchesTheBandTable(GameTestHelper helper) throws IOException {
        int crust = CampaignBands.crustRows();
        JsonObject settings = read(helper, "/data/deepcharter/worldgen/noise_settings/campaign.json")
                .getAsJsonObject();
        JsonObject noise = settings.getAsJsonObject("noise");
        expect(helper, noise.get("min_y").getAsInt() == CampaignBands.worldMinY(), "the noise settings' min_y");
        expect(
                helper,
                noise.get("height").getAsInt() == CampaignBands.worldTopExclusive() - CampaignBands.worldMinY(),
                "the noise settings' height");
        expect(
                helper,
                settings.get("sea_level").getAsInt() == noise.get("min_y").getAsInt(),
                "sea_level must be min_y (ADR 0029)");
        expect(
                helper,
                !settings.has("aquifers_enabled")
                        || !settings.get("aquifers_enabled").getAsBoolean(),
                "aquifers");
        expect(
                helper,
                settings.get("default_block").getAsString().equals(GREEN_SHALE),
                "the default block is the rock of layer 2 and unbuilt depth");

        // The density takes each layer's profile from its shipped noise settings, moved to the layer's band, and picks
        // between them by band.
        JsonElement density = settings.getAsJsonObject("noise_router").get("final_density");
        List<String> ranges = new ArrayList<>();
        collectRanges(density, ranges);
        List<String> expectedRanges = Collections.nCopies(3, range(CampaignBands.LAYER_1));
        expect(
                helper,
                ranges.equals(expectedRanges),
                "the density picks between the layers by %s, found %s",
                expectedRanges,
                ranges);
        Set<Integer> gradients = new TreeSet<>();
        collectGradientCoordinates(density, gradients);
        Set<Integer> expectedGradients =
                new TreeSet<>(List.of(CampaignBands.SURFACE.minY() - 4, CampaignBands.SURFACE.minY()));
        for (int number = 1; number <= CampaignBands.LAYERS.size(); number++) {
            Set<Integer> local = new TreeSet<>();
            collectGradientCoordinates(
                    read(helper, "/data/deepcharter/worldgen/noise_settings/layer_" + number + ".json"), local);
            for (int y : local) {
                expectedGradients.add(CampaignBands.layer(number).minY() + y);
            }
        }
        expect(
                helper,
                gradients.equals(expectedGradients),
                "gradient coordinates %s, expected the layers' moved to their bands: %s",
                gradients,
                expectedGradients);
        expect(
                helper,
                density.toString().contains("\"deepcharter:surface/density\""),
                "the density must use the shipped surface density");

        // The material rule's Y conditions, outside the shipped surface rule that it embeds.
        JsonElement shippedSurface = read(helper, "/data/deepcharter/worldgen/material_rule/surface.json");
        expect(
                helper,
                settings.get("material_rule").toString().contains(shippedSurface.toString()),
                "the surface band's rule must embed the shipped surface rule");
        Set<Integer> anchors = new TreeSet<>();
        collectAnchors(settings.get("material_rule"), shippedSurface, anchors, helper);
        Set<Integer> expectedAnchors = new TreeSet<>(List.of(
                CampaignBands.SURFACE.minY(),
                CampaignBands.LAYER_1.minY(),
                CampaignBands.LAYER_1.minY() + crust,
                CampaignBands.LAYER_2.minY(),
                CampaignBands.LAYER_2.minY() + crust));
        expect(helper, anchors.equals(expectedAnchors), "rule anchors %s, expected %s", anchors, expectedAnchors);

        JsonObject dimension = read(helper, "/data/minecraft/worldgen/world_preset/flat_all_dimensions.json")
                .getAsJsonObject()
                .getAsJsonObject("dimensions")
                .getAsJsonObject(SAMPLE);
        JsonObject type = read(helper, "/data/deepcharter/dimension_type/campaign_sample.json")
                .getAsJsonObject();
        expect(
                helper,
                dimension.get("type").getAsString().equals("deepcharter:campaign_sample")
                        && type.get("min_y").getAsInt() == CampaignBands.worldMinY()
                        && type.get("height").getAsInt()
                                == CampaignBands.worldTopExclusive() - CampaignBands.worldMinY(),
                "the dimension type spans the bands");
        JsonObject generator = dimension.getAsJsonObject("generator");
        expect(
                helper,
                generator.get("settings").getAsString().equals("deepcharter:campaign"),
                "campaign_sample uses the campaign noise settings");
        JsonObject source = generator.getAsJsonObject("biome_source");
        expect(
                helper,
                source.get("type").getAsString().equals("deepcharter:bands")
                        && source.get("surface_min_y").getAsInt() == CampaignBands.SURFACE.minY(),
                "the biome source is bands, with the surface from its band");
        JsonArray layers = source.getAsJsonArray("layers");
        expect(helper, layers.size() == CampaignBands.LAYERS.size(), "one biome entry per layer");
        for (int i = 0; i < layers.size(); i++) {
            Band band = CampaignBands.LAYERS.get(i);
            JsonObject entry = layers.get(i).getAsJsonObject();
            expect(
                    helper,
                    entry.get("min_y").getAsInt() == band.minY()
                            && entry.get("height").getAsInt() == band.height(),
                    "the biome source's layer %d is not the band table's",
                    i + 1);
        }
        JsonElement shippedBiomes = read(helper, "/data/minecraft/dimension/overworld.json")
                .getAsJsonObject()
                .getAsJsonObject("generator")
                .getAsJsonObject("biome_source")
                .get("biomes");
        expect(
                helper,
                source.getAsJsonObject("surface").get("biomes").equals(shippedBiomes),
                "the surface biomes are not the shipped overworld's");
        helper.succeed();
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theBiomeFeaturesHaveNoOrderCycle(GameTestHelper helper) {
        ChunkGenerator generator = helper.getLevel().getChunkSource().getGenerator();
        List<Holder<Biome>> biomes = List.copyOf(generator.getBiomeSource().possibleBiomes());
        Set<String> ids = new TreeSet<>();
        biomes.forEach(
                biome -> ids.add(biome.unwrapKey().orElseThrow().identifier().toString()));
        // Every biome of the world shares this one generator, so one feature sorter sees them all.
        expect(
                helper,
                ids.containsAll(List.of(
                        "deepcharter:regolith_plains",
                        "deepcharter:salvage_yard",
                        "deepcharter:topsoil_claims",
                        "deepcharter:deep_claim",
                        "deepcharter:upper_levels",
                        "deepcharter:prospectors_run",
                        "deepcharter:unbuilt_depth")),
                "the generator's biomes are %s",
                ids);
        // The sorter throws on a cycle, which is what the generator would do at the first chunk's features.
        try {
            FeatureSorter.buildFeaturesPerStep(
                    biomes, biome -> biome.value().getGenerationSettings().features(), true);
        } catch (IllegalStateException e) {
            throw fail(helper, "the biome features have an order cycle: %s", e.getMessage());
        }
        helper.succeed();
    }

    private static boolean isCrustRow(int y) {
        return CampaignBands.LAYERS.stream()
                .anyMatch(layer -> y >= layer.minY() && y < layer.minY() + CampaignBands.crustRows());
    }

    private static Band bandOf(int y) {
        return CampaignBands.ALL.stream()
                .filter(band -> y >= band.minY() && y < band.topExclusive())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no band at y=" + y));
    }

    private static String range(Band band) {
        return band.minY() + ".." + band.topExclusive();
    }

    /** The {@code range_choice} bounds on the way down the density, outermost first, as {@code min..max}. */
    private static void collectRanges(JsonElement node, List<String> out) {
        if (node.isJsonObject()) {
            JsonObject object = node.getAsJsonObject();
            if (object.has("type") && object.get("type").getAsString().equals("minecraft:range_choice")) {
                out.add(object.get("min_inclusive").getAsInt() + ".."
                        + object.get("max_exclusive").getAsInt());
            }
            object.entrySet().forEach(entry -> collectRanges(entry.getValue(), out));
        } else if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(element -> collectRanges(element, out));
        }
    }

    /** The coordinates of every {@code gradient} in a density function. */
    private static void collectGradientCoordinates(JsonElement node, Set<Integer> out) {
        if (node.isJsonObject()) {
            JsonObject object = node.getAsJsonObject();
            if (object.has("type") && object.get("type").getAsString().equals("minecraft:gradient")) {
                out.add(object.get("from_coordinate").getAsInt());
                out.add(object.get("to_coordinate").getAsInt());
            }
            object.entrySet().forEach(entry -> collectGradientCoordinates(entry.getValue(), out));
        } else if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(element -> collectGradientCoordinates(element, out));
        }
    }

    /** The absolute anchors of the {@code y_above} conditions of a rule, leaving out the embedded {@code skip} rule. */
    private static void collectAnchors(JsonElement node, JsonElement skip, Set<Integer> out, GameTestHelper helper) {
        if (node.equals(skip)) {
            return;
        }
        if (node.isJsonObject()) {
            JsonObject object = node.getAsJsonObject();
            if (object.has("type") && object.get("type").getAsString().equals("minecraft:y_above")) {
                JsonObject anchor = object.getAsJsonObject("anchor");
                if (!anchor.has("absolute")) {
                    throw fail(helper, "a band rule must use absolute Ys, not %s", anchor);
                }
                out.add(anchor.get("absolute").getAsInt());
            }
            object.entrySet().forEach(entry -> collectAnchors(entry.getValue(), skip, out, helper));
        } else if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(element -> collectAnchors(element, skip, out, helper));
        }
    }

    private static void expectOnly(
            GameTestHelper helper, Map<String, Set<String>> found, Band band, Set<String> allowed) {
        Set<String> blocks = new TreeSet<>(found.getOrDefault(band.name(), Set.of()));
        blocks.removeAll(allowed);
        expect(helper, blocks.isEmpty(), "%s holds blocks it should not: %s", band.name(), blocks);
    }

    /** Every column of the sample on a grid: ground level, generating the chunks. */
    private static List<BlockPos> sampledColumns() {
        List<BlockPos> columns = new ArrayList<>();
        int span = SAMPLE_CHUNKS * 16;
        for (int x = 0; x < span; x += SAMPLE_STEP) {
            for (int z = 0; z < span; z += SAMPLE_STEP) {
                columns.add(new BlockPos((SAMPLE_CHUNK_X << 4) + x, 0, (SAMPLE_CHUNK_Z << 4) + z));
            }
        }
        return columns;
    }

    private static String idAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getChunk(pos).getBlockState(pos);
        return state.is(Blocks.AIR)
                ? "minecraft:air"
                : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String biomeAt(ServerLevel level, BlockPos pos) {
        // The cell the generator stored, not level.getBiome(pos): that one blurs a band's edge with the cell next to
        // it.
        Optional<String> key = level.getChunk(pos)
                .getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2)
                .unwrapKey()
                .map(k -> k.identifier().toString());
        return key.orElseThrow(() -> new IllegalStateException("a biome with no key at " + pos));
    }

    @FormatMethod
    private static void expect(
            GameTestHelper helper, boolean ok, @FormatString String format, @Nullable Object... args) {
        if (!ok) {
            throw fail(helper, format, args);
        }
    }

    @FormatMethod
    private static RuntimeException fail(GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }

    private static JsonElement read(GameTestHelper helper, String resource) throws IOException {
        var stream = CampaignTerrainTest.class.getResourceAsStream(resource);
        if (stream == null) {
            throw fail(helper, "missing resource %s", resource);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }
}
