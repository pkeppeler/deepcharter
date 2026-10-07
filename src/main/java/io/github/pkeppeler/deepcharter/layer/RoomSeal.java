package io.github.pkeppeler.deepcharter.layer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.ore.HazardBlocks;

/**
 * Seals a room cut into rock against what worldgen leaves in it: the one-block shell around a box (its sides, floor and
 * ceiling) has every fluid (lava included) and every gas pocket replaced by stone. Plain air stays: a room that opens
 * onto a cave or onto open space must still be a way out.
 * The stone is placed with update flags that wake nothing, so no lava next to the shell is told that its neighbour changed.
 * Seal before the box itself is cut, or the cut's updates reach the lava first. Anything that carves a room in a layer
 * (a breach pocket, a test's room) should call it.
 */
public final class RoomSeal {
	private RoomSeal() {
	}

	/** Seals the shell around the box from {@code min} to {@code max}, both inclusive; the box itself is left alone. */
	public static void seal(ServerLevel level, BlockPos min, BlockPos max) {
		for (BlockPos pos : BlockPos.betweenClosed(min.offset(-1, -1, -1), max.offset(1, 1, 1))) {
			boolean inside = pos.getX() >= min.getX() && pos.getX() <= max.getX() && pos.getY() >= min.getY()
					&& pos.getY() <= max.getY() && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
			if (!inside && !level.isOutsideBuildHeight(pos) && needsSealing(level.getBlockState(pos))) {
				level.setBlock(pos.immutable(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
	}

	private static boolean needsSealing(BlockState state) {
		return !state.getFluidState().isEmpty() || state.is(HazardBlocks.GAS_POCKET);
	}
}
