package io.github.pkeppeler.deepcharter.surface;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Grows one fungal stalk at its origin: an ordinary column of two to {@link SurfaceTuning#ordinaryMaxHeight()} blocks,
 * biased to the short ones, or, with the chance {@code greatChance} and where there is room, a {@link GreatStalk}. The
 * placed features pick the ground; this decides what grows on it.
 *
 * @param inChunk whether a great stalk must fit in its origin's chunk, so what grows does not depend on the order chunks are
 *     decorated in (the layers' stalks, ADR 0088); the surface's stalks predate it and keep their shape without
 * @param greatChance the chance a stalk is a great stalk, {@link SurfaceTuning#greatStalkChance()} unless the feature
 *     says: layer 3's stalks say 0, since its great stalks grow from the giant fungus mix ({@link GiantFungusFeature},
 *     #1202)
 */
public record FungalStalkFeature(boolean inChunk, double greatChance) implements Feature {
    public static final MapCodec<FungalStalkFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.BOOL.optionalFieldOf("in_chunk", false).forGetter(FungalStalkFeature::inChunk),
                    Codec.doubleRange(0, 1)
                            .optionalFieldOf("great_chance", SurfaceTuning.DEFAULT.greatStalkChance())
                            .forGetter(FungalStalkFeature::greatChance))
            .apply(instance, FungalStalkFeature::new));

    private static final int ORDINARY_MIN_HEIGHT = 2;

    @Override
    public MapCodec<FungalStalkFeature> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        SurfaceTuning tuning = SurfaceTuning.DEFAULT;
        if (random.nextDouble() < greatChance && GreatStalk.place(level, random, origin, inChunk)) {
            return true;
        }
        Block stalk = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("deepcharter", "fungal_stalk"));
        BlockState state = stalk.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        int span = tuning.ordinaryMaxHeight() - ORDINARY_MIN_HEIGHT + 1;
        // Biased to the bottom, as the column it replaces was: a roll of the roll.
        int height = ORDINARY_MIN_HEIGHT + random.nextInt(random.nextInt(span) + 1);
        int placed = 0;
        while (placed < height && level.getBlockState(origin.above(placed)).isAir()) {
            level.setBlock(origin.above(placed), state, Block.UPDATE_CLIENTS);
            placed++;
        }
        return placed > 0;
    }
}
