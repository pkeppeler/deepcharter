package io.github.pkeppeler.deepcharter.surface;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block of a great stalk, its thick trunk or its cap: a full cube that drops one fungal stalk, like every stalk block. Breaking one
 * brings down the great stalk above it ({@link StalkCollapse}).
 */
final class GreatStalkBlock extends Block {
    GreatStalkBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void affectNeighborsAfterRemoval(
            BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        StalkCollapse.removed(level, pos);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        StalkCollapse.step(level, pos);
    }
}
