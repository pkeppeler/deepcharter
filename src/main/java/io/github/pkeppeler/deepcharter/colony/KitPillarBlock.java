package io.github.pkeppeler.deepcharter.colony;

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

/** A kit member that runs along one of three axes: a beam, a girder, a pipe, a cable. Its cross-section is a square of {@code size}. */
final class KitPillarBlock extends RotatedPillarBlock {
	private final Map<Direction.Axis, VoxelShape> shapes;

	KitPillarBlock(Properties properties, double size) {
		super(properties);
		this.shapes = Shapes.rotateAllAxis(Block.cube(size, size, 16));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapes.get(state.getValue(AXIS));
	}
}
