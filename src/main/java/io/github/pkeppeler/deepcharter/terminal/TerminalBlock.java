package io.github.pkeppeler.deepcharter.terminal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

import io.github.pkeppeler.deepcharter.texture.TextureProperties;

/**
 * A terminal, of whichever {@link TerminalType} owns this block. Using it, with or without an item in hand, asks the server to
 * open it ({@link Terminals#open}). It cannot be broken in survival: see {@link TerminalTypes}.
 *
 * <p>{@link TextureProperties#ACTIVE} says whether its type is online, which picks the lit screen or the dark one. It starts true
 * only for a type that needs no repair; {@link TerminalActivity} keeps it right from then on.
 */
public final class TerminalBlock extends BaseEntityBlock {
	TerminalBlock(Properties properties, boolean alwaysOnline) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(TextureProperties.ACTIVE, alwaysOnline));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(BlockStateProperties.HORIZONTAL_FACING, TextureProperties.ACTIVE);
	}

	/** However the block got here (a player, the colony builder, a command), it shows whether its type is online. */
	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (level instanceof ServerLevel serverLevel) {
			TerminalActivity.sync(serverLevel, pos);
		}
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TerminalBlockEntity(pos, state);
	}

	/** {@link BaseEntityBlock} makes its blocks invisible, because most block entities draw themselves. This one is a model. */
	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer) {
			Terminals.open(serverPlayer, pos);
		}
		return InteractionResult.SUCCESS;
	}
}
