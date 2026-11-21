package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cutover's first check (#803, slice 8 of #214): {@code campaign_sample}'s surface band makes the same ground as
 * {@code surface_sample}, the copy of today's overworld. A grid of columns is compared on its top Y (the height query the generator
 * answers, no chunk made), its top block in a made chunk and its biome. The top Y and the biome must be identical: this check found
 * columns of solid ground that rose from the surface to the top of the band in 0.5% of the columns, because the layers' density terms
 * ran above their bands, and the campaign density now ends them at the surface band's floor. The top block of a made chunk differs in
 * about 14% of the columns (the surface rule and the structures' terrain adaptation are not the same in the two worlds), and the
 * features (crates, stalks, boulders) are not all at the same place, because a feature's place depends on the order of the generation
 * steps and the campaign biome source has more biomes: both are reported, not asserted. This test goes with {@code surface_sample},
 * when the cutover deletes it.
 */
public class SurfaceBandParityTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(SurfaceBandParityTest.class);
    private static final ResourceKey<Level> SURFACE_SAMPLE = key("surface_sample");
    private static final ResourceKey<Level> CAMPAIGN_SAMPLE = key("campaign_sample");
    private static final int MAX_TICKS = 100_000;
    /** The height query runs over a square this many blocks either side of the origin, this far apart. */
    private static final int HEIGHT_SPAN = 1024;

    private static final int HEIGHT_STEP = 4;
    /** The chunks that are made: this many to a side, this far apart, centred on the colony (more of them run the test out of memory). */
    private static final int CHUNK_STEP = 3;

    private static final int CHUNKS_PER_SIDE = 9;
    /** A feature stands at most this far above the ground. */
    private static final int FEATURE_REACH = 48;

    private record Feature(BlockPos pos, String block) {}

    @GameTest(maxTicks = MAX_TICKS)
    public void theCampaignSurfaceBandMakesTheGroundOfTheSurfaceSample(GameTestHelper helper) {
        ServerLevel surface = ClientLight.serverLevel(helper.getLevel().getServer(), SURFACE_SAMPLE);
        ServerLevel campaign = ClientLight.serverLevel(helper.getLevel().getServer(), CAMPAIGN_SAMPLE);
        int heightColumns = 0;
        int heightDiffs = 0;
        List<String> firstHeightDiffs = new ArrayList<>();
        for (int x = -HEIGHT_SPAN; x < HEIGHT_SPAN; x += HEIGHT_STEP) {
            for (int z = -HEIGHT_SPAN; z < HEIGHT_SPAN; z += HEIGHT_STEP) {
                heightColumns++;
                int a = baseHeight(surface, x, z);
                int b = baseHeight(campaign, x, z);
                if (a != b && heightDiffs++ < 5) {
                    firstHeightDiffs.add(x + " " + z + ": " + a + " and " + b);
                }
            }
        }
        int columns = 0;
        int blockDiffs = 0;
        int biomeDiffs = 0;
        int features = 0;
        int unmatched = 0;
        int moved = 0;
        double farthest = 0;
        double total = 0;
        int half = CHUNKS_PER_SIDE / 2;
        for (int i = -half; i <= half; i++) {
            for (int j = -half; j <= half; j++) {
                LevelChunk a = surface.getChunk(i * CHUNK_STEP, j * CHUNK_STEP);
                LevelChunk b = campaign.getChunk(i * CHUNK_STEP, j * CHUNK_STEP);
                List<Feature> featuresA = new ArrayList<>();
                List<Feature> featuresB = new ArrayList<>();
                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        int x = a.getPos().getMinBlockX() + dx;
                        int z = a.getPos().getMinBlockZ() + dz;
                        int height = baseHeight(surface, x, z);
                        BlockPos ground = new BlockPos(x, height - 1, z);
                        if (dx % 5 == 0 && dz % 5 == 0) {
                            columns++;
                            if (!blockAt(a, ground).equals(blockAt(b, ground)) && blockDiffs++ < 6) {
                                LOGGER.info(
                                        "[surface-parity] {}: {} and {}, above {} and {}",
                                        ground.toShortString(),
                                        blockAt(a, ground),
                                        blockAt(b, ground),
                                        blockAt(a, ground.above()),
                                        blockAt(b, ground.above()));
                            }
                            biomeDiffs += biomeAt(surface, ground).equals(biomeAt(campaign, ground)) ? 0 : 1;
                        }
                        collect(a, x, z, height, featuresA);
                        collect(b, x, z, height, featuresB);
                    }
                }
                features += featuresA.size();
                for (Feature f : featuresA) {
                    double nearest = featuresB.stream()
                            .filter(g -> g.block().equals(f.block()))
                            .mapToDouble(g -> Math.sqrt(g.pos().distSqr(f.pos())))
                            .min()
                            .orElse(-1);
                    if (nearest < 0) {
                        unmatched++;
                    } else if (nearest > 0) {
                        moved++;
                        farthest = Math.max(farthest, nearest);
                        total += nearest;
                    }
                }
            }
        }
        LOGGER.info(
                "[surface-parity] height query: {} of {} columns differ {}; of {} columns in made chunks, top block differs in {}, biome in {}",
                heightDiffs,
                heightColumns,
                firstHeightDiffs,
                columns,
                blockDiffs,
                biomeDiffs);
        LOGGER.info(
                "[surface-parity] {} feature blocks in surface_sample: {} not at the same place, {} of them with no same block in the chunk, farthest {} blocks, mean {}",
                features,
                moved + unmatched,
                unmatched,
                Math.round(farthest * 10) / 10.0,
                moved == 0 ? 0 : Math.round(total / moved * 10) / 10.0);
        if (heightDiffs != 0 || biomeDiffs != 0) {
            throw helper.assertionException(
                    "the ground differs: the height of %s of %s columns, the biome of %s of %s columns",
                    heightDiffs, heightColumns, biomeDiffs, columns);
        }
        helper.succeed();
    }

    private static int baseHeight(ServerLevel level, int x, int z) {
        RandomState random = level.getChunkSource().randomState();
        return level.getChunkSource()
                .getGenerator()
                .getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
    }

    private static String blockAt(LevelChunk chunk, BlockPos pos) {
        return BuiltInRegistries.BLOCK
                .getKey(chunk.getBlockState(pos).getBlock())
                .toString();
    }

    private static String biomeAt(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().orElseThrow().identifier().toString();
    }

    /** The blocks above the ground of one column: the features. */
    private static void collect(LevelChunk chunk, int x, int z, int height, List<Feature> out) {
        for (int y = height; y < height + FEATURE_REACH; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            String id = blockAt(chunk, pos);
            if (!id.equals("minecraft:air")) {
                out.add(new Feature(pos, id));
            }
        }
    }

    private static ResourceKey<Level> key(String name) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", name));
    }
}
