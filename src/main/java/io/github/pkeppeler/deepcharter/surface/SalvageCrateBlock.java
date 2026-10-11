package io.github.pkeppeler.deepcharter.surface;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * A Company salvage crate (art direction section 11). It breaks by hand into {@code loot_table/blocks/salvage_crate.json}. Its model is
 * drawn facing north and turned by its blockstate file, which is where the shape of the crate is a pack's to change; the block itself
 * fills the whole cell.
 */
final class SalvageCrateBlock extends HorizontalDirectionalBlock {
	SalvageCrateBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}
}
