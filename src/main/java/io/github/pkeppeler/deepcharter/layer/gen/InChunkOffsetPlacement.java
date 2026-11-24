package io.github.pkeppeler.deepcharter.layer.gen;

import com.mojang.serialization.MapCodec;
import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.OffsetPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/**
 * Vanilla's {@code minecraft:offset}, with the same fields and draws, but kept in the chunk of the position it moves: an offset that
 * would leave the chunk is mirrored back in at the chunk's edge. A patch spread across a chunk's side lands in a neighbour that may be
 * decorated before or after it, so what grows there would depend on the order chunks are generated in; kept in its own chunk it grows
 * the same whichever comes first (#972). Mirroring keeps every try, so a patch's count is unchanged.
 */
public record InChunkOffsetPlacement(OffsetPlacement offset) implements PlacementModifier {
    public static final MapCodec<InChunkOffsetPlacement> CODEC =
            OffsetPlacement.CODEC.xmap(InChunkOffsetPlacement::new, InChunkOffsetPlacement::offset);

    private static final int CHUNK = 16;

    public static void register() {
        Registry.register(
                BuiltInRegistries.PLACEMENT_MODIFIER_TYPE,
                Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "in_chunk_offset"),
                CODEC);
    }

    @Override
    public void modify(PlacementContext context, RandomSource random, BlockPos pos, Consumer<BlockPos> output) {
        int firstX = SectionPos.blockToSectionCoord(pos.getX()) * CHUNK;
        int firstZ = SectionPos.blockToSectionCoord(pos.getZ()) * CHUNK;
        offset.modify(
                context,
                random,
                pos,
                moved -> output.accept(new BlockPos(
                        firstX + mirrored(moved.getX() - firstX),
                        moved.getY(),
                        firstZ + mirrored(moved.getZ() - firstZ))));
    }

    /** {@code local}, a column relative to the chunk's first, mirrored at the chunk's edges until it lies in the chunk. */
    private static int mirrored(int local) {
        int folded = Math.floorMod(local, 2 * CHUNK);
        return folded < CHUNK ? folded : 2 * CHUNK - 1 - folded;
    }

    @Override
    public MapCodec<InChunkOffsetPlacement> codec() {
        return CODEC;
    }
}
