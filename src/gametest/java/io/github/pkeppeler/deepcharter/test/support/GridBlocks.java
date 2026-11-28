package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.outpost.OutpostShell.Terrain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * A block world in memory, for tests of a rule that reads blocks only (the shell's exposure, #1239): solid stone everywhere, with boxes and
 * single blocks cut into it. A later write wins, and a box costs one entry however large, so a cave of a million cells is cheap to state.
 */
public final class GridBlocks implements BlockGetter {
    private record Fill(BoundingBox box, BlockState state) {}

    private final List<Fill> fills = new ArrayList<>();
    private final Map<BlockPos, BlockState> singles = new HashMap<>();

    /** Fills the box between {@code a} and {@code b}, both inclusive. */
    public GridBlocks fill(BlockPos a, BlockPos b, BlockState state) {
        fills.add(new Fill(BoundingBox.fromCorners(a, b), state));
        return this;
    }

    /** Sets one block. */
    public GridBlocks set(BlockPos pos, BlockState state) {
        singles.put(pos.immutable(), state);
        return this;
    }

    /** Everything loaded. */
    public Terrain terrain() {
        return new Terrain(this, _ -> true);
    }

    /** Everything loaded but the chunks {@code unloaded} says are not (by {@code ChunkPos.asLong}). */
    public Terrain terrainWithout(LongPredicate unloaded) {
        return new Terrain(this, chunk -> !unloaded.test(chunk));
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState single = singles.get(pos);
        if (single != null) {
            return single;
        }
        for (int i = fills.size() - 1; i >= 0; i--) {
            if (fills.get(i).box().isInside(pos)) {
                return fills.get(i).state();
            }
        }
        return Blocks.STONE.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return 4096;
    }

    @Override
    public int getMinY() {
        return -2048;
    }
}
