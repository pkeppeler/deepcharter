package io.github.pkeppeler.deepcharter.surface;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block of a giant fungus, which drops one fungal stalk like every stalk block: a great stalk's full-cube trunk or
 * cap, and the class every parasol and spire block extends with its own shape ({@link GiantFungusBlocks}). Breaking one
 * brings down what it holds up at once ({@link StalkCollapse}).
 */
class GreatStalkBlock extends Block {
    private final boolean cap;

    /** {@code cap} is whether this is a cap block, which hangs from what it joins, rather than a trunk block, which stands. */
    GreatStalkBlock(Properties properties, boolean cap) {
        super(properties);
        this.cap = cap;
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
}
