package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.CrustBoundaries;
import io.github.pkeppeler.deepcharter.layer.FloorCrust;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.gen.BandBiomeSource;
import io.github.pkeppeler.deepcharter.layer.gen.BandBiomeSource.Layer;
import io.github.pkeppeler.deepcharter.layer.gen.GrainedCrustPass;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.material.PushReaction;

/**
 * Server GameTests for the decoys of the grained crust (#216, ADR 0060): one draw for each boundary between bands, from the world seed and
 * the boundary's own Y, which the layer data can override. The draw and the chunk pass run on synthetic band lists, the way the campaign
 * generator runs on its own ({@link GrainedCrustPass#swap}), because a two-layer campaign has one boundary to draw until layer 3's band
 * lands. {@code deepcharter:campaign_grain_sample}, a test-preset copy of the campaign world with layer 1's floor set to grained and layer
 * 2's left to the seed, proves the same through real generation, chunk by chunk.
 */
public class GrainedCrustDecoyTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final String GRAIN_SAMPLE = "deepcharter:campaign_grain_sample";
    private static final int MAX_TICKS = 6000;

    /** The synthetic bands: boundaries this far apart, from the top of the first, and a seed list that is not special. */
    private static final int BAND_HEIGHT = 64;

    /** The synthetic bands start under the surface band, as the campaign's own do, and run down through the unbuilt depth. */
    private static final int BAND_TOP = CampaignBands.SURFACE.minY();

    private static final long[] SEEDS = {0L, 1L, -1L, 42L, 8_675_309L, Long.MIN_VALUE, Long.MAX_VALUE, 0x5EEDL};

    /** The real generation's sample: this many chunks on a side, at this chunk, in a world of its own. */
    private static final int SAMPLE_CHUNK = 10;

    private static final int SAMPLE_CHUNKS = 2;
    private static final int SAMPLE_STEP = 5;

    /**
     * The swap puts the grained crust where the breach crust was, so it holds what #1110 gave the breach crust: the blast resistance of bedrock,
     * no push, and a place in the wither and dragon tags. {@code BreachCrustMoversTest} proves the movers against these properties.
     */
    @GameTest
    public void aDecoyIsAsBlastProofAndImmovableAsTheBreachCrust(GameTestHelper helper) {
        BlockState grained = LayerBlocks.GRAINED_CRUST.defaultBlockState();
        expect(
                helper,
                LayerBlocks.GRAINED_CRUST.getExplosionResistance() >= Blocks.BEDROCK.getExplosionResistance(),
                "the grained crust's blast resistance is %s",
                LayerBlocks.GRAINED_CRUST.getExplosionResistance());
        expect(
                helper,
                grained.getPistonPushReaction() == PushReaction.IMMOVEABLE,
                "a piston can move the grained crust: its push reaction is %s",
                grained.getPistonPushReaction());
        expect(helper, grained.is(BlockTags.WITHER_IMMUNE), "the grained crust is not in minecraft:wither_immune");
        expect(helper, grained.is(BlockTags.DRAGON_IMMUNE), "the grained crust is not in minecraft:dragon_immune");
        helper.succeed();
    }

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE)
    public void theSameSeedGivesTheSameBoundaries(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Layer> layers = bands(level, 24, 0);
        boolean seedsDiffer = false;
        Map<Integer, Boolean> first = null;
        for (long seed : SEEDS) {
            Map<Integer, Boolean> once = kinds(level, seed, layers, new ChunkPos(0, 0));
            Map<Integer, Boolean> again = kinds(level, seed, layers, new ChunkPos(0, 0));
            expect(helper, once.equals(again), "seed %d drew different boundaries on a second run", seed);
            if (first == null) {
                first = once;
            } else if (!first.equals(once)) {
                seedsDiffer = true;
            }
        }
        expect(helper, seedsDiffer, "every seed drew the same boundaries: the seed is not read");
        helper.succeed();
    }

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE)
    public void aboutAThirdOfDrawnBoundariesAreGrained(GameTestHelper helper) {
        int drawn = 0;
        int grained = 0;
        for (long seed : SEEDS) {
            for (int index = 0; index < 512; index++) {
                drawn++;
                if (CrustBoundaries.grained(seed, boundaryY(index), FloorCrust.DRAW)) {
                    grained++;
                }
            }
        }
        double share = grained / (double) drawn;
        expect(
                helper,
                share > 0.30 && share < 0.37,
                "%d of %d drawn boundaries are grained: %.3f",
                grained,
                drawn,
                share);
        helper.succeed();
    }

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE)
    public void addingABandRedrawsNoExistingBoundary(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Layer> all = bands(level, 12, 0);
        expect(helper, all.size() > 9, "too few bands to take one from the middle");
        // The same bands with one added below the last, one above the first, and one between two (the others keep
        // their Ys), and the list in another order: the position in the list is no part of a boundary's identity.
        List<Layer> base = new ArrayList<>(all);
        base.remove(8);
        base.remove(0);
        base.removeLast();
        Layer top = all.getFirst();
        Layer middle = all.get(8);
        Layer last = all.getLast();
        List<List<Layer>> grown = new ArrayList<>();
        List<Layer> below = new ArrayList<>(base);
        below.add(last);
        grown.add(below);
        List<Layer> above = new ArrayList<>(base);
        above.addFirst(top);
        grown.add(above);
        List<Layer> between = new ArrayList<>(base);
        between.add(7, middle);
        grown.add(between);
        List<Layer> reversed = new ArrayList<>(base);
        Collections.reverse(reversed);
        grown.add(reversed);
        long grainedBase = 0;
        for (long seed : SEEDS) {
            Map<Integer, Boolean> before = kinds(level, seed, base, new ChunkPos(3, -7));
            grainedBase += before.values().stream().filter(kind -> kind).count();
            for (List<Layer> after : grown) {
                Map<Integer, Boolean> now = kinds(level, seed, after, new ChunkPos(3, -7));
                for (Map.Entry<Integer, Boolean> boundary : before.entrySet()) {
                    expect(
                            helper,
                            boundary.getValue().equals(now.get(boundary.getKey())),
                            "seed %d: the boundary at %d changed kind when a band was added",
                            seed,
                            boundary.getKey());
                }
            }
        }
        expect(
                helper,
                grainedBase > 0 && grainedBase < (long) SEEDS.length * base.size(),
                "the base bands drew one kind only");
        helper.succeed();
    }

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE)
    public void aBoundaryIsTheSameKindInEveryChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Layer> layers = bands(level, 24, 0);
        for (long seed : SEEDS) {
            Map<Integer, Boolean> reference = kinds(level, seed, layers, new ChunkPos(0, 0));
            for (ChunkPos chunk : List.of(new ChunkPos(1, 0), new ChunkPos(-5, 9), new ChunkPos(1000, -1000))) {
                expect(
                        helper,
                        reference.equals(kinds(level, seed, layers, chunk)),
                        "seed %d: chunk %s drew other boundaries than chunk 0 0",
                        seed,
                        chunk);
            }
        }
        helper.succeed();
    }

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE)
    public void layerDataOverridesTheDraw(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (long seed : SEEDS) {
            for (FloorCrust setting : new FloorCrust[] {FloorCrust.NORMAL, FloorCrust.GRAINED}) {
                List<Layer> layers = bands(level, 24, 0, setting);
                Map<Integer, Boolean> kinds = kinds(level, seed, layers, new ChunkPos(0, 0));
                for (boolean grained : kinds.values()) {
                    expect(
                            helper,
                            grained == (setting == FloorCrust.GRAINED),
                            "seed %d: %s did not hold",
                            seed,
                            setting);
                }
            }
        }
        helper.succeed();
    }

    /** Layer data that says nothing of a floor leaves it to the seed, and the word {@code floor_crust} reads back as the setting. */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE)
    public void aLayerThatSaysNothingOfItsFloorIsLeftToTheSeed(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        JsonObject source = read(helper, "/data/deepcharter/dimension/campaign_sample.json")
                .getAsJsonObject()
                .getAsJsonObject("generator")
                .getAsJsonObject("biome_source");
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        List<FloorCrust> told = BandBiomeSource.CODEC.codec().parse(ops, source).getOrThrow().layers().stream()
                .map(Layer::floorCrust)
                .toList();
        expect(
                helper,
                told.equals(List.of(FloorCrust.NORMAL, FloorCrust.NORMAL, FloorCrust.NORMAL)),
                "the shipped data reads as %s",
                told);
        for (JsonElement layer : source.getAsJsonArray("layers")) {
            layer.getAsJsonObject().remove("floor_crust");
        }
        List<FloorCrust> silent = BandBiomeSource.CODEC.codec().parse(ops, source).getOrThrow().layers().stream()
                .map(Layer::floorCrust)
                .toList();
        expect(
                helper,
                silent.equals(List.of(FloorCrust.DRAW, FloorCrust.DRAW, FloorCrust.DRAW)),
                "layers with no floor_crust read as %s",
                silent);
        helper.succeed();
    }

    /** The crust pass only swaps the breach crust: rock, air and the rows above stay. */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE)
    public void theSwapTouchesTheCrustRowsOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Layer> layers = bands(level, 2, 0, FloorCrust.GRAINED);
        ChunkAccess chunk = chunkWithCrust(level, layers, new ChunkPos(0, 0));
        int rows = CampaignBands.crustRows();
        int edge = layers.getFirst().minY();
        chunk.setBlockState(new BlockPos(0, edge + 1, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockPos above = new BlockPos(1, edge + rows, 1);
        chunk.setBlockState(above, LayerBlocks.PACKED_SHALE.defaultBlockState(), Block.UPDATE_CLIENTS);
        int swapped = GrainedCrustPass.swap(chunk, 1L, layers);
        expect(helper, swapped == 2 * rows * 256 - 1, "swapped %d blocks, not the crust rows less the air", swapped);
        expect(helper, chunk.getBlockState(above).is(LayerBlocks.PACKED_SHALE), "the row above the crust changed");
        expect(
                helper,
                chunk.getBlockState(new BlockPos(0, edge + 1, 0)).isAir(),
                "air in the crust rows was filled with crust");
        helper.succeed();
    }

    /** The pass is in the unbuilt-depth biome, which every column of a campaign world holds, so no band's biome has to carry it. */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE)
    public void everyCampaignColumnRunsTheCrustPass(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<Biome> unbuilt = level.registryAccess()
                .lookupOrThrow(Registries.BIOME)
                .getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse("deepcharter:unbuilt_depth")));
        boolean carried = unbuilt.value().getGenerationSettings().features().stream()
                .anyMatch(step -> step.stream()
                        .anyMatch(feature -> feature.unwrapKey()
                                .orElseThrow()
                                .identifier()
                                .equals(Identifier.parse("deepcharter:grained_crust_pass"))));
        expect(helper, carried, "the unbuilt-depth biome does not carry the crust pass");
        BandBiomeSource source =
                (BandBiomeSource) level.getChunkSource().getGenerator().getBiomeSource();
        expect(
                helper,
                source.possibleBiomes().stream()
                        .anyMatch(biome -> biome.is(unbuilt.unwrapKey().orElseThrow())),
                "the campaign world's biome source holds no unbuilt-depth biome");
        helper.succeed();
    }

    /** The first breach is breach crust, in the layer data: handbook chapter 8 teaches it. */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theFirstBreachIsNormalCrustInTheLayerData(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Layer> layers =
                ((BandBiomeSource) level.getChunkSource().getGenerator().getBiomeSource()).layers();
        expect(
                helper,
                layers.getFirst().floorCrust() == FloorCrust.NORMAL,
                "layer 1's floor is %s",
                layers.getFirst().floorCrust());
        for (BlockPos column : sample()) {
            for (int y = CampaignBands.LAYER_1.minY();
                    y < CampaignBands.LAYER_1.minY() + CampaignBands.crustRows();
                    y++) {
                expect(
                        helper,
                        level.getChunk(column).getBlockState(column.atY(y)).is(LayerBlocks.BREACH_CRUST),
                        "the first breach has no breach crust at %s",
                        column.atY(y));
            }
        }
        helper.succeed();
    }

    /**
     * Through real generation: layer 1's floor is grained by its layer data, in every column of every chunk sampled; layer 2's is what
     * the draw says for this world's seed, the same in every one; and the crust is the block's default state, no flag set by the
     * neighbours that were placed after it.
     */
    @GameTest(dimension = GRAIN_SAMPLE, maxTicks = MAX_TICKS)
    public void aGeneratedBoundaryIsTheSameKindInEveryChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Layer> layers =
                ((BandBiomeSource) level.getChunkSource().getGenerator().getBiomeSource()).layers();
        expect(
                helper,
                layers.getFirst().floorCrust() == FloorCrust.GRAINED,
                "layer 1's floor is %s",
                layers.getFirst().floorCrust());
        expect(
                helper,
                layers.get(1).floorCrust() == FloorCrust.DRAW,
                "layer 2's floor is %s",
                layers.get(1).floorCrust());
        BlockState grained = LayerBlocks.GRAINED_CRUST.defaultBlockState();
        for (Layer layer : layers) {
            boolean expected = CrustBoundaries.grained(level.getSeed(), layer.minY(), layer.floorCrust());
            for (BlockPos column : sample()) {
                for (int y = layer.minY(); y < layer.minY() + CampaignBands.crustRows(); y++) {
                    BlockState state = level.getChunk(column).getBlockState(column.atY(y));
                    if (expected) {
                        expect(
                                helper,
                                state == grained,
                                "layer at %d: %s at %s, not the grained crust",
                                layer.minY(),
                                state,
                                column.atY(y));
                    } else {
                        expect(
                                helper,
                                state.is(LayerBlocks.BREACH_CRUST),
                                "layer at %d: %s at %s, not the breach crust",
                                layer.minY(),
                                state,
                                column.atY(y));
                    }
                }
            }
        }
        helper.succeed();
    }

    /** Layers of {@code count} synthetic bands, top to bottom, every boundary at a Y that is its own whatever the list holds. */
    private static List<Layer> bands(ServerLevel level, int count, int first) {
        return bands(level, count, first, FloorCrust.DRAW);
    }

    private static List<Layer> bands(ServerLevel level, int count, int first, FloorCrust setting) {
        Holder<Biome> biome =
                level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        List<Layer> layers = new ArrayList<>();
        for (int index = first; index < first + count; index++) {
            layers.add(new Layer(boundaryY(index), BAND_HEIGHT, List.of(biome, biome, biome), setting));
        }
        return layers;
    }

    /** The Y of band {@code index}'s floor, a boundary of its own: it does not depend on the other bands. */
    private static int boundaryY(int index) {
        return BAND_TOP - (index + 1) * BAND_HEIGHT;
    }

    /**
     * Which of {@code layers}' boundaries the crust pass makes grained in the chunk at {@code at}, by the Y of the boundary: the crust
     * is laid as the material rule lays it (breach crust, every row) and the pass runs over it.
     */
    private static Map<Integer, Boolean> kinds(ServerLevel level, long seed, List<Layer> layers, ChunkPos at) {
        ChunkAccess chunk = chunkWithCrust(level, layers, at);
        GrainedCrustPass.swap(chunk, seed, layers);
        Map<Integer, Boolean> kinds = new TreeMap<>();
        for (Layer layer : layers) {
            boolean grained = chunk.getBlockState(new BlockPos(at.getMinBlockX(), layer.minY(), at.getMinBlockZ()))
                    .is(LayerBlocks.GRAINED_CRUST);
            // The kind is the same in every row and column of the boundary, or this fails here and not in the
            // comparison.
            for (int y = layer.minY(); y < layer.minY() + CampaignBands.crustRows(); y++) {
                for (int x = 0; x < 16; x += 5) {
                    for (int z = 0; z < 16; z += 5) {
                        boolean here = chunk.getBlockState(
                                        new BlockPos(at.getMinBlockX() + x, y, at.getMinBlockZ() + z))
                                .is(LayerBlocks.GRAINED_CRUST);
                        if (here != grained) {
                            throw new IllegalStateException(
                                    "the boundary at " + layer.minY() + " is mixed in chunk " + at);
                        }
                    }
                }
            }
            kinds.put(layer.minY(), grained);
        }
        return kinds;
    }

    private static ChunkAccess chunkWithCrust(ServerLevel level, List<Layer> layers, ChunkPos at) {
        ProtoChunk chunk = new ProtoChunk(at, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
        BlockState crust = LayerBlocks.BREACH_CRUST.defaultBlockState();
        for (Layer layer : layers) {
            for (int y = layer.minY(); y < layer.minY() + CampaignBands.crustRows(); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        chunk.setBlockState(
                                new BlockPos(at.getMinBlockX() + x, y, at.getMinBlockZ() + z),
                                crust,
                                Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        return chunk;
    }

    private static JsonElement read(GameTestHelper helper, String resource) throws IOException {
        var stream = GrainedCrustDecoyTest.class.getResourceAsStream(resource);
        if (stream == null) {
            throw failure(helper, "missing resource %s", resource);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }

    private static List<BlockPos> sample() {
        List<BlockPos> columns = new ArrayList<>();
        for (int x = 0; x < SAMPLE_CHUNKS * 16; x += SAMPLE_STEP) {
            for (int z = 0; z < SAMPLE_CHUNKS * 16; z += SAMPLE_STEP) {
                columns.add(new BlockPos((SAMPLE_CHUNK << 4) + x, 0, (SAMPLE_CHUNK << 4) + z));
            }
        }
        return columns;
    }
}
