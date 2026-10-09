package io.github.pkeppeler.deepcharter.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import io.github.pkeppeler.deepcharter.texture.TextureProperties;

/**
 * A caged sodium lamp, the Company's light (art direction section 8). Lit ({@link TextureProperties#ACTIVE}) it gives full block
 * light and its glass glows; dark, it gives none. Whoever builds with it sets the state: the colony and the wrecks (#244, #248).
 */
public final class CompanyLamp extends Block {
	/** A lit lamp's block light. */
	private static final int LIGHT = 15;
	private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 15, 13);

	CompanyLamp(Properties properties) {
		super(properties.lightLevel(state -> state.getValue(TextureProperties.ACTIVE) ? LIGHT : 0));
		registerDefaultState(stateDefinition.any().setValue(TextureProperties.ACTIVE, true));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(TextureProperties.ACTIVE);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}
}
