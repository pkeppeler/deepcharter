package io.github.pkeppeler.deepcharter.sound.ambience;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Company punch clock of the hall in Shift Change (#938): scenery that nothing collides with and no rule touches. A player near it
 * may, rarely, hear it call an hour, and sometimes the wrong one ({@code AmbienceTicker}, a client part, over {@link ClockCaller}).
 * Breaking it costs nothing and drops nothing.
 */
public final class CompanyClockBlock extends Block {
    /** The foot, the post and the face. */
    private static final VoxelShape SHAPE = Block.box(4, 0, 6, 12, 16, 10);

    CompanyClockBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
