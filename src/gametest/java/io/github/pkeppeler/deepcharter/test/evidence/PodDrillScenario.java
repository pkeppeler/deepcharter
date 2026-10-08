package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.RoomSeal;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

/**
 * Evidence scenario "pod-drill": the real player drills a pod down through a row of stone and the three rows of
 * breach crust of layer 1, and crosses into layer 2. The pilot only holds sprint. The tank is topped up first: at that
 * depth the crust alone takes about 80% of a stock tank.
 */
public class PodDrillScenario extends EvidenceScenario {
	private static final int X = 4000;
	private static final int Z = 4000;
	private static final int FLOOR_Y = 4;
	private static final int TICKS_PER_FRAME = 16;
	private static final int MAX_TICKS = 3000;
	private static final int ARRIVAL_FRAMES = 8;
	private static final float LOOK_DOWN = 55f;

	@Override
	protected String name() {
		return "pod-drill";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				buildShaftRoom(one);
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
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
			frame(context);

			singleplayer.getServer().runOnServer(server -> ((PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle()).setFuel(100f));
			context.getInput().holdKey(options -> options.keySprint);
			int ticks = 0;
			while (!inLayer(context, 2) && ticks < MAX_TICKS) {
				context.waitTicks(TICKS_PER_FRAME);
				ticks += TICKS_PER_FRAME;
				frame(context);
			}
			context.getInput().releaseKey(options -> options.keySprint);
			if (!inLayer(context, 2)) {
				throw new AssertionError("The pod did not drill through the crust into layer_2 within " + MAX_TICKS + " ticks");
			}
			context.runOnClient(client -> client.player.setXRot(LOOK_DOWN));
			for (int i = 0; i < ARRIVAL_FRAMES; i++) {
				context.waitTicks(8);
				frame(context);
			}
			screenshot(context, "pod-drill-layer-2");
		}
	}

	private static boolean inLayer(ClientGameTestContext context, int layer) {
		return context.computeOnClient(client -> client.level != null && client.level.dimension().equals(LayerChain.dimension(layer)));
	}

	/** Crust rows, a row of stone under the pod, an open room above, and glowstone to light the shaft. */
	private static void buildShaftRoom(ServerLevel level) {
		// Seal first: worldgen scatters lava through layer 1's rock, and lava beside the room would flow in and into the shaft.
		RoomSeal.seal(level, new BlockPos(X - 5, 0, Z - 5), new BlockPos(X + 5, FLOOR_Y + 9, Z + 5));
		box(level, 0, 2, LayerBlocks.BREACH_CRUST);
		box(level, 3, FLOOR_Y - 1, Blocks.STONE);
		box(level, FLOOR_Y, FLOOR_Y + 9, Blocks.AIR);
		for (BlockPos lamp : new BlockPos[] {
				new BlockPos(X + 1, 3, Z - 1), new BlockPos(X - 2, 3, Z),
				new BlockPos(X + 1, 1, Z), new BlockPos(X - 2, 1, Z - 1), new BlockPos(X + 1, 0, Z - 1),
				new BlockPos(X + 5, FLOOR_Y + 2, Z), new BlockPos(X - 5, FLOOR_Y + 2, Z),
				new BlockPos(X, FLOOR_Y + 2, Z + 5), new BlockPos(X, FLOOR_Y + 2, Z - 5)}) {
			level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
		}
	}

	private static void box(ServerLevel level, int yFrom, int yTo, Block block) {
		for (int x = X - 5; x <= X + 5; x++) {
			for (int y = yFrom; y <= yTo; y++) {
				for (int z = Z - 5; z <= Z + 5; z++) {
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
				}
			}
		}
	}
}
