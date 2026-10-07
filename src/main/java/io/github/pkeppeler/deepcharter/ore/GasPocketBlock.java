package io.github.pkeppeler.deepcharter.ore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Stone with a pocket of gas in it: whatever removes it, a drill or a hand, sets the gas off. */
public class GasPocketBlock extends Block {
	public GasPocketBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
		GasHazard.vent(level, pos);
	}
}
