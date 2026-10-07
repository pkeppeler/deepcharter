package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.layer.LayerChain;

/**
 * Evidence scenario "m2-ore-placement": a room cut into the rock of Deep Claim (layer 1) and of Prospector's Run
 * (layer 2), so that the wall shows what worldgen put there: ore, Company rock and lava. The wall is the
 * generated rock and nothing is placed by the scenario; the room is the one with the most lava of the few tried.
 * The player has night vision, because the layers are dark by design.
 */
public class OrePlacementScenario extends EvidenceScenario {
	private static final int START_X = 2500;
	private static final int START_Z = 2500;
	private static final int STEP = 20;
	private static final int ROOMS_TRIED = 12;
	private static final int ROOM_LENGTH = 14;
	private static final int ROOM_HALF_WIDTH = 8;
	private static final int ROOM_HEIGHT = 6;
	private static final int PAN_FRAMES = 20;
	private static final float EAST = -90f;

	@Override
	protected String name() {
		return "m2-ore-placement";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setGameMode(GameType.CREATIVE);
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				player.setPermanentlyInvulnerable(true);
				player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
			});
			// Deep Claim is the bottom third of layer 1 (y 3 to 63); Prospector's Run the bottom third of layer 2 (y 3 to 84).
			show(context, singleplayer, 1, 20, "layer-1-deep-claim", false);
			show(context, singleplayer, 2, 30, "layer-2-prospectors-run", true);
		}
	}

	private void show(ClientGameTestContext context, TestSingleplayerContext singleplayer, int layer, int y, String shot, boolean pan) {
		BlockPos room = singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(layer));
			BlockPos best = null;
			int most = -1;
			for (int i = 0; i < ROOMS_TRIED; i++) {
				BlockPos candidate = new BlockPos(START_X + STEP * i, y, START_Z);
				int lava = lavaOnWall(level, candidate);
				if (lava > most) {
					most = lava;
					best = candidate;
				}
			}
			cut(level, best);
			return best;
		});
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(layer));
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.teleportTo(level, room.getX() - 5.5, room.getY(), room.getZ() + 0.5, Set.of(), EAST, 0f, true);
		});
		context.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(layer)));
		context.waitTicks(40);
		screenshot(context, shot);
		if (pan) {
			for (int i = 0; i < PAN_FRAMES; i++) {
				float yaw = EAST - 35f + 70f * i / (PAN_FRAMES - 1);
				singleplayer.getServer().runOnServer(server -> {
					ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
					player.teleportTo(server.getLevel(LayerChain.dimension(layer)), room.getX() - 5.5, room.getY(), room.getZ() + 0.5, Set.of(), yaw, 0f, true);
				});
				context.waitTicks(2);
				frame(context);
			}
		}
	}

	/** Lava in the wall of the room (its east face, one block high above the floor to the ceiling, across its width). */
	private static int lavaOnWall(ServerLevel level, BlockPos origin) {
		int lava = 0;
		for (int dz = -ROOM_HALF_WIDTH; dz <= ROOM_HALF_WIDTH; dz++) {
			for (int dy = 0; dy < ROOM_HEIGHT; dy++) {
				if (level.getBlockState(origin.offset(ROOM_LENGTH - 6, dy, dz)).is(Blocks.LAVA)) {
					lava++;
				}
			}
		}
		return lava;
	}

	/** Air in a box with no neighbour updates, so the lava around it stays still. */
	private static void cut(ServerLevel level, BlockPos origin) {
		for (int dx = -6; dx < ROOM_LENGTH - 6; dx++) {
			for (int dz = -ROOM_HALF_WIDTH; dz <= ROOM_HALF_WIDTH; dz++) {
				for (int dy = 0; dy < ROOM_HEIGHT; dy++) {
					level.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}
	}
}
