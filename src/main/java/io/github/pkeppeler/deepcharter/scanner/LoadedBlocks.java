package io.github.pkeppeler.deepcharter.scanner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * The blocks of a level that are already loaded, and air everywhere else. A server reads a {@link ScanSlice} through this: a plain
 * {@code Level.getBlockState} on the server loads, and generates, the chunk it asks about, and a scanner can reach farther than the
 * chunks around a pod are loaded. The client's level reads an unloaded chunk as air, so the two agree on what a scanner shows.
 */
public record LoadedBlocks(Level level) implements BlockGetter {
    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return loaded(pos) ? level.getBlockState(pos) : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return loaded(pos) ? level.getFluidState(pos) : Fluids.EMPTY.defaultFluidState();
    }

    private boolean loaded(BlockPos pos) {
        return isLoaded(level, pos);
    }

    /** True when the chunk holding {@code pos} is loaded. */
    public static boolean isLoaded(Level level, BlockPos pos) {
        return level.getChunkSource()
                .hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /**
     * True when a block at {@code pos} can be set or broken without loading a chunk. A change tells the four blocks around it,
     * which reads their chunks and loads one that is not there, so the position and each side of it must be loaded. Code that
     * changes a block on a tick path asks this first and leaves the block alone when the answer is no.
     */
    public boolean canChange(BlockPos pos) {
        return loaded(pos) && loaded(pos.north()) && loaded(pos.south()) && loaded(pos.east()) && loaded(pos.west());
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinY() {
        return level.getMinY();
    }
}
