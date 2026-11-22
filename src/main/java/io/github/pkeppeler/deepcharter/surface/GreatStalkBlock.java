package io.github.pkeppeler.deepcharter.surface;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * A block of a great stalk, its thick trunk or its cap: a full cube that drops one fungal stalk, like every stalk block. Breaking one
 * brings down the great stalk above it ({@link StalkCollapse}).
 */
final class GreatStalkBlock extends Block {
    private final boolean cap;

    /** {@code cap} is whether this is a cap block, which hangs from what it joins, rather than a trunk block, which stands. */
    GreatStalkBlock(Properties properties, boolean cap) {
        super(properties);
        this.cap = cap;
        registerDefaultState(defaultBlockState().setValue(StalkCollapse.FALLING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(StalkCollapse.FALLING);
    }

    /** Whether this is a cap block. */
    boolean isCap() {
        return cap;
    }

    @Override
    protected void affectNeighborsAfterRemoval(
            BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        StalkCollapse.removed(level, pos, state);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel server) {
            StalkCollapse.placed(server, pos, state);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        StalkCollapse.step(level, pos);
    }
}
