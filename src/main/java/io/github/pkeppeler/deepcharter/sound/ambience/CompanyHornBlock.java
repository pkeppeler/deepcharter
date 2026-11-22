package io.github.pkeppeler.deepcharter.sound.ambience;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A Company public-address horn, hung on a chain from the roof of the punch clock's hall, or set on a post where the roof is out of reach. It is scenery that nothing collides with and no rule
 * touches: a player near it hears the ambience's fragments from it ({@code AmbienceTicker}, a client part), after a crackle of warm-up.
 * Breaking it costs nothing and drops nothing.
 */
public final class CompanyHornBlock extends Block {
    /** The bracket, the neck and the bell, from the top of the block down. */
    private static final VoxelShape SHAPE = Block.box(4, 6, 4, 12, 16, 12);

    CompanyHornBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
