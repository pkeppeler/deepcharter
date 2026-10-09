package io.github.pkeppeler.deepcharter.colony;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * One tile of a Company sign: an enamel plate on the wall behind it. Which letters or which part of the badge a tile shows is
 * resource data (tools/colony/signs.py draws the tiles); a sign is a row of these blocks, each with its own tile.
 */
final class KitSignBlock extends KitFacingBlock {
	/** How many tiles the signs may use; tools/colony/kit.py writes a model for each. */
	static final int TILES = 55;
	static final IntegerProperty TILE = IntegerProperty.create("tile", 0, TILES - 1);

	KitSignBlock(Properties properties) {
		super(properties, Block.box(0, 0, 14, 16, 16, 16));
		registerDefaultState(defaultBlockState().setValue(TILE, 0));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(TILE);
	}
}
