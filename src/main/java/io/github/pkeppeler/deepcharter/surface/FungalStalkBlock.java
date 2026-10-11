package io.github.pkeppeler.deepcharter.surface;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A pale fungal stalk, the alien stand-in for a tree (art direction section 11). It runs along an axis like a log, so a stack of them is a
 * trunk. Every look a pack gives it is narrower than the cell, so its shape is a square of {@code WIDTH} pixels.
 */
final class FungalStalkBlock extends RotatedPillarBlock {
	private static final double WIDTH = 8;
	private final Map<Direction.Axis, VoxelShape> shapes = Shapes.rotateAllAxis(Block.cube(WIDTH, WIDTH, 16));

	FungalStalkBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapes.get(state.getValue(AXIS));
	}
}
