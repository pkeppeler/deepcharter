package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.layer.RoomSeal;

/**
 * Cuts a room into the rock of a layer dimension, the one way a test or scenario may. It seals the shell with
 * {@link RoomSeal} first and fills the box after, because worldgen leaves lava in layer rock and a room that is opened
 * unsealed floods (PR 229). The gametest generator in {@code gradle/gametest.gradle} fails the build on a direct air write
 * in a file that touches a layer dimension.
 */
public final class RoomCarver {
	private RoomCarver() {
	}

	/** Seals the shell around the box from {@code min} to {@code max}, both inclusive, then fills the box with {@code fill}, notifying clients only. */
	public static void carve(ServerLevel level, BlockPos min, BlockPos max, BlockState fill) {
		carve(level, min, max, fill, Block.UPDATE_CLIENTS);
	}

	/** As {@link #carve(ServerLevel, BlockPos, BlockPos, BlockState)}, for a box given as inclusive coordinate ranges. */
	public static void carve(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block fill) {
		carve(level, new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2), fill.defaultBlockState());
	}

	/** As {@link #carve(ServerLevel, BlockPos, BlockPos, BlockState)}, with the update flags of each block written. */
	public static void carve(ServerLevel level, BlockPos min, BlockPos max, BlockState fill, int flags) {
		RoomSeal.seal(level, min, max);
		fill(level, min, max, fill, flags);
	}

	private static void fill(ServerLevel level, BlockPos min, BlockPos max, BlockState fill, int flags) {
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			level.setBlock(pos, fill, flags);
		}
	}
}
