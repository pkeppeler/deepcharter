package io.github.pkeppeler.deepcharter.layer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The film of moss that goes before and round {@link LayerBlocks#OVERGROWTH} (#974): a thin layer on the faces of the rock round a cell, so the
 * growth reads as spreading over a surface and not as a cube set into a hole. It has no collision, takes no time to break, blocks no light and
 * drops nothing; {@link Overgrowth} places it and this block takes itself away once nothing holds it ({@link Overgrowth#anchored}).
 */
public final class OvergrowthVeneerBlock extends MultifaceBlock {
    /** Ticks between two checks that the film is still held. */
    private static final int CHECK_TICKS = 40;

    public OvergrowthVeneerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        level.scheduleTick(pos, this, CHECK_TICKS);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (Overgrowth.anchored(level, pos)) {
            level.scheduleTick(pos, this, CHECK_TICKS);
        } else {
            level.removeBlock(pos, false);
        }
    }
}
