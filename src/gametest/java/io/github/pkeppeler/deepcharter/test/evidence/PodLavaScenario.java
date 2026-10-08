package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

/**
 * Evidence scenario "pod-lava": a pod sits in a closed room, lava appears under it, and the hull gauge drops until the hull
 * is down to a third. The pilot is in creative mode so that the pod, not the pilot, is what the lava ends.
 */
public class PodLavaScenario extends EvidenceScenario {
	private static final int X = 5000;
	private static final int Z = 5000;
	private static final int FLOOR_Y = 4;
	private static final int TICKS_PER_FRAME = 6;
	private static final int MAX_TICKS = 600;
	private static final float STOP_HULL = 35f;
	private static final float LOOK_DOWN = 25f;

	@Override
	protected String name() {
		return "pod-lava";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				box(one, FLOOR_Y - 1, FLOOR_Y - 1, 6, Blocks.STONE);
				box(one, FLOOR_Y, FLOOR_Y + 8, 6, Blocks.STONE);
				// room-carver: a room cut inside the solid stone box built just above, so no worldgen lava is in or beside it; the scenario places its own lava
				box(one, FLOOR_Y, FLOOR_Y + 7, 5, Blocks.AIR);
				for (BlockPos lamp : new BlockPos[] {
						new BlockPos(X + 4, FLOOR_Y + 3, Z), new BlockPos(X - 4, FLOOR_Y + 3, Z),
						new BlockPos(X, FLOOR_Y + 3, Z + 4), new BlockPos(X, FLOOR_Y + 3, Z - 4)}) {
					one.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
				}
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setGameMode(GameType.CREATIVE);
				player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), 0, LOOK_DOWN, true);
				PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
				pod.setPos(X, FLOOR_Y, Z);
				one.addFreshEntity(pod);
				if (!player.startRiding(pod)) {
					throw new AssertionError("the player could not mount the pod");
				}
			});
			context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof PodEntity
					&& client.level.dimension().equals(LayerChain.dimension(1)));
			context.runOnClient(client -> {
				client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				client.player.setXRot(LOOK_DOWN);
			});
			context.waitTicks(20);
			for (int i = 0; i < 6; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			screenshot(context, "pod-lava-before");

			singleplayer.getServer().runOnServer(server -> {
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				for (int x = X - 2; x <= X + 2; x++) {
					for (int z = Z - 2; z <= Z + 2; z++) {
						one.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.LAVA.defaultBlockState(), 3);
					}
				}
			});
			int ticks = 0;
			while (podHull(singleplayer) > STOP_HULL && ticks < MAX_TICKS) {
				context.waitTicks(TICKS_PER_FRAME);
				ticks += TICKS_PER_FRAME;
				frame(context);
			}
			if (podHull(singleplayer) > STOP_HULL) {
				throw new AssertionError("The lava did not take the hull down to " + STOP_HULL + " within " + MAX_TICKS + " ticks");
			}
			for (int i = 0; i < 4; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			screenshot(context, "pod-lava-after");
		}
	}

	private static float podHull(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server ->
				((PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle()).hull());
	}

	/** A square of {@code block} {@code radius} blocks out from the pod's column, from {@code yFrom} to {@code yTo}. */
	private static void box(ServerLevel level, int yFrom, int yTo, int radius, Block block) {
		for (int x = X - radius; x <= X + radius; x++) {
			for (int y = yFrom; y <= yTo; y++) {
				for (int z = Z - radius; z <= Z + radius; z++) {
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
				}
			}
		}
	}
}
