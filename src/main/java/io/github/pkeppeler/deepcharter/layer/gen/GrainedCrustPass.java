package io.github.pkeppeler.deepcharter.layer.gen;

import com.mojang.serialization.MapCodec;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.CrustBoundaries;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.gen.BandBiomeSource.Layer;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Turns the crust of a boundary grained where the boundary is (#216, ADR 0060): the breach crust the material rule laid at the floor of
 * each layer becomes {@code grained_crust} in every chunk, for a layer whose {@link CrustBoundaries#grained} says so.
 *
 * <p>The material rule cannot do this: it has no seed, and a boundary must be the same kind in every chunk. So the rule always lays the
 * breach crust and this pass, one placed feature with no placement modifiers, swaps it. The feature is in the unbuilt-depth biome, which
 * every column of the campaign world holds, so it runs once for each chunk, at the chunk's corner, and only touches that chunk's blocks.
 * A layer added to the world's {@code deepcharter:bands} data needs nothing here.
 *
 * <p>The swap is a chunk write that tells no neighbour, so the crust it sets has none of its {@code changed} flags up.
 */
public record GrainedCrustPass() implements Feature {
    public static final MapCodec<GrainedCrustPass> CODEC = MapCodec.unit(GrainedCrustPass::new);

    public static void register() {
        Registry.register(
                BuiltInRegistries.FEATURE_TYPE,
                Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "grained_crust_pass"),
                CODEC);
    }

    @Override
    public MapCodec<GrainedCrustPass> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        if (!(generator.getBiomeSource() instanceof BandBiomeSource source)) {
            return false;
        }
        return swap(level.getChunk(origin), level.getSeed(), source.layers()) > 0;
    }

    /**
     * Swaps the breach crust at the floor of each of {@code layers} that is grained under {@code seed} for the grained crust, in one chunk.
     * Returns how many blocks it set.
     */
    public static int swap(ChunkAccess chunk, long seed, List<Layer> layers) {
        BlockState grained = LayerBlocks.GRAINED_CRUST.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int swapped = 0;
        for (Layer layer : layers) {
            if (!CrustBoundaries.grained(seed, layer.minY(), layer.floorCrust())) {
                continue;
            }
            for (int y = layer.minY(); y < layer.minY() + CampaignBands.crustRows(); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        pos.set(
                                chunk.getPos().getMinBlockX() + x,
                                y,
                                chunk.getPos().getMinBlockZ() + z);
                        if (chunk.getBlockState(pos).is(LayerBlocks.BREACH_CRUST)) {
                            chunk.setBlockState(pos, grained, 0);
                            swapped++;
                        }
                    }
                }
            }
        }
        return swapped;
    }
}
