package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

/**
 * Throwaway evidence scenario "tall-stack" for issue 185: the "pod-drill" scenario, but in one dimension that holds
 * layer 1's rock above layer 2's, with a breach crust between them. The pod drills the crust and falls on into layer 2
 * with no teleport, no fade, and no change of dimension.
 */
public class TallStackScenario extends EvidenceScenario {
	private static final ResourceKey<Level> STACK = ResourceKey.create(Registries.DIMENSION,
			Identifier.fromNamespaceAndPath("deepcharter", "tall_spike_stack"));
	private static final int X = 4000;
	private static final int Z = 4000;
	/** Layer 1 starts here; the crust is the three rows from here up. */
	private static final int SPLIT = 256;
	private static final int FLOOR_Y = SPLIT + 4;
	private static final int TICKS_PER_FRAME = 16;
	private static final int MAX_TICKS = 4000;
	private static final int FALL_FRAMES = 14;
	private static final float LOOK_DOWN = 55f;

	@Override
	protected String name() {
		return "tall-stack";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel level = server.getLevel(STACK);
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						level.getChunk(X / 16 + dx, Z / 16 + dz);
					}
				}
				buildShaftRoom(level);
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.teleportTo(level, X, FLOOR_Y, Z, Set.of(), 0, LOOK_DOWN, true);
				PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
				pod.setPos(X, FLOOR_Y, Z);
				player.setPermanentlyInvulnerable(true);
				level.addFreshEntity(pod);
				if (!player.startRiding(pod)) {
					throw new AssertionError("the player could not mount the pod");
				}
			});
			context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof PodEntity
					&& client.level.dimension().equals(STACK));
			context.runOnClient(client -> {
				client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				client.player.setXRot(LOOK_DOWN);
			});
			context.waitTicks(20);
			frame(context);

			singleplayer.getServer().runOnServer(server -> ((PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle()).setFuel(100f));
			context.getInput().holdKey(options -> options.keySprint);
			int ticks = 0;
			while (podY(context) > SPLIT - 15 && ticks < MAX_TICKS) {
				context.waitTicks(TICKS_PER_FRAME);
				ticks += TICKS_PER_FRAME;
				requireStack(context);
				frame(context);
			}
			context.getInput().releaseKey(options -> options.keySprint);
			if (podY(context) > SPLIT - 15) {
				throw new AssertionError("The pod did not drill through the crust and fall into layer 2's range within " + MAX_TICKS + " ticks");
			}
			context.runOnClient(client -> client.player.setXRot(LOOK_DOWN));
			for (int i = 0; i < FALL_FRAMES; i++) {
				context.waitTicks(8);
				requireStack(context);
				frame(context);
			}
			screenshot(context, "tall-stack-below-the-crust");
		}
	}

	private static double podY(ClientGameTestContext context) {
		return context.computeOnClient(client -> client.player.getY());
	}

	/** The point of the spike: the client never leaves the one dimension. */
	private static void requireStack(ClientGameTestContext context) {
		if (!context.computeOnClient(client -> client.level.dimension().equals(STACK))) {
			throw new AssertionError("The client left the stack dimension: a crossing happened");
		}
	}

	/** Crust rows at the layer boundary, a row of stone under the pod, an open room above, and glowstone to light the shaft. */
	private static void buildShaftRoom(ServerLevel level) {
		// The 6 rows of rock the density interpolation leaves under the crust cost more fuel than the tank holds, so clear them.
		box(level, SPLIT - 7, SPLIT - 1, Blocks.AIR);
		// Layer 2's void under the crust is about 30 blocks deep and a pod that lands from it at speed is wrecked: a slime block floor catches it.
		box(level, SPLIT - 18, SPLIT - 18, Blocks.SLIME_BLOCK);
		box(level, SPLIT, SPLIT + 2, LayerBlocks.BREACH_CRUST);
		box(level, SPLIT + 3, FLOOR_Y - 1, Blocks.STONE);
		box(level, FLOOR_Y, FLOOR_Y + 9, Blocks.AIR);
		for (BlockPos lamp : new BlockPos[] {
				new BlockPos(X + 1, SPLIT + 3, Z - 1), new BlockPos(X - 2, SPLIT + 3, Z),
				new BlockPos(X + 1, SPLIT + 1, Z), new BlockPos(X - 2, SPLIT + 1, Z - 1), new BlockPos(X + 1, SPLIT, Z - 1),
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
