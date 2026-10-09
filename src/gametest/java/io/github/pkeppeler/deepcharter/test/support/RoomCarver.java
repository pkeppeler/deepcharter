package io.github.pkeppeler.deepcharter.test.support;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.layer.RoomSeal;

/**
 * The one way a test or scenario may open a room in the rock of a layer dimension. It seals the shell with {@link RoomSeal}
 * first and fills the box after, because worldgen leaves lava in layer rock and a room that is opened unsealed floods
 * (PR 229). The gametest generator in {@code gradle/gametest.gradle} fails the build on a direct air write in a file that
 * touches a layer dimension. A solid fill (a stone bed) goes through here too, so that its shell is sealed to the same depth.
 */
public final class RoomCarver {
	private static final int ARRIVAL_RADIUS = 4;
	private static final int ARRIVAL_HEIGHT = 5;

	private RoomCarver() {
	}

	/** Seals the shell around the box between the corners {@code a} and {@code b} in either order, both inclusive, then fills the box with {@code fill}, notifying clients only. */
	public static void carve(ServerLevel level, BlockPos a, BlockPos b, BlockState fill) {
		carve(level, a, b, fill, Block.UPDATE_CLIENTS);
	}

	/** As {@link #carve(ServerLevel, BlockPos, BlockPos, BlockState)}, for a box given as inclusive coordinate ranges. */
	public static void carve(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block fill) {
		carve(level, new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2), fill.defaultBlockState());
	}

	/** As {@link #carve(ServerLevel, BlockPos, BlockPos, BlockState)}, with the update flags of each block written. */
	public static void carve(ServerLevel level, BlockPos a, BlockPos b, BlockState fill, int flags) {
		BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		RoomSeal.seal(level, min, max);
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			level.setBlock(pos, fill, flags);
		}
	}

	/** The layer 2 arrival point is in lava, which burns a pod seated there; a sealed room of air around the first player keeps it out. */
	public static void carveAroundFirstPlayer(TestServerContext server) {
		server.runOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			BlockPos feet = player.blockPosition();
			carve((ServerLevel) player.level(), feet.offset(-ARRIVAL_RADIUS, 0, -ARRIVAL_RADIUS), feet.offset(ARRIVAL_RADIUS, ARRIVAL_HEIGHT, ARRIVAL_RADIUS),
					Blocks.AIR.defaultBlockState());
		});
	}
}
