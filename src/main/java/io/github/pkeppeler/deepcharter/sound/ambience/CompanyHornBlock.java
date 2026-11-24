package io.github.pkeppeler.deepcharter.sound.ambience;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A Company public-address horn, hung on a chain from the roof of the punch clock's hall, or from a beam on a post where the roof is out of reach. It is scenery that nothing collides with and no rule
 * touches: a player near it hears the ambience's fragments from it ({@code AmbienceTicker}, a client part), after a crackle of warm-up.
 * Breaking it costs nothing and drops nothing.
 */
public final class CompanyHornBlock extends Block {
    /** The two-way trumpet, and the hanger and plate that carry it from the top of the block. */
    private static final VoxelShape SHAPE = Shapes.or(Block.box(4, 1, 0, 12, 13, 16), Block.box(4, 13, 5, 12, 16, 11));

    CompanyHornBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
