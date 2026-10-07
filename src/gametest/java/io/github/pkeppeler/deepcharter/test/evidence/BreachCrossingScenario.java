package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerChain;

/**
 * Evidence scenario "breach-crossing": a recording of a player falling through a breach, with the
 * fade and the transmission, plus screenshots of the same view in layer 1 and layer 2 to compare
 * their darkness and fog.
 */
public class BreachCrossingScenario extends EvidenceScenario {
	private static final double X = 2000.5;
	private static final double Z = 2000.5;
	/** Where the player stands. The terrain is noise, so a stone floor and an open room are built here first. */
	private static final int STANDING_Y = 100;
	private static final int ROOM_LENGTH = 56;
	private static final int ROOM_HEIGHT = 10;
	private static final int SHAFT_TOP = 30;
	private static final int PATIENCE = 400;
	/** Frames recorded once the transmission has finished typing. */
	private static final int TAIL_FRAMES = 12;

	@Override
	protected String name() {
		return "breach-crossing";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			// A transmission goes to a charter, so the player founds one: the crossing then brings its transmission.
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setPermanentlyInvulnerable(true);
				if (Charters.found(server, player.getUUID(), "Breach Crew").isPresent()) {
					throw new AssertionError("founding the charter should succeed");
				}
			});

			// The same row of quartz pillars at the same distances, so only the light and fog differ.
			lookDownTheRow(context, singleplayer, 1);
			screenshot(context, "layer-1-darkness");

			// Down a shaft that is open through the crust, looking at it.
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				BlockPos column = BlockPos.containing(X, 0, Z);
				for (int y = one.getMinY(); y <= SHAFT_TOP; y++) {
					one.setBlock(column.atY(y), Blocks.AIR.defaultBlockState(), 3);
				}
				// Generate the arrival area in layer 2 now so the client has less to wait for after the crossing.
				ServerLevel two = server.getLevel(LayerChain.dimension(2));
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						two.getChunk(column.getX() / 16 + dx, column.getZ() / 16 + dz);
					}
				}
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.teleportTo(one, X, SHAFT_TOP - 1, Z, Set.of(), 0, 70, true);
			});
			context.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(1)));

			int typedAt = -1;
			for (int tick = 0; tick < PATIENCE; tick++) {
				// Vanilla covers the HUD with a "Loading terrain" screen while the client waits for the new layer's
				// chunks. It is not part of the effect being shown, so leave those frames out.
				if (context.computeOnClient(client -> client.gui.screen() == null)) {
					frame(context);
				}
				boolean typed = context.computeOnClient(client -> TransmissionOverlay.typed());
				if (typed && typedAt < 0) {
					typedAt = tick;
				}
				if (typedAt >= 0 && tick - typedAt >= TAIL_FRAMES) {
					break;
				}
				context.waitTick();
			}
			if (typedAt < 0) {
				throw new AssertionError("The transmission never finished typing within " + PATIENCE + " frames");
			}

			lookDownTheRow(context, singleplayer, 2);
			screenshot(context, "layer-2-darkness");
		}
	}

	/** Stands the player on layer {@code layer}'s floor of stone, facing a row of pillars, and waits for the view to load. */
	private void lookDownTheRow(ClientGameTestContext context, TestSingleplayerContext singleplayer, int layer) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(layer));
			int standing = STANDING_Y;
			int column = (int) X;
			int row = (int) Z;
			for (int x = column - 3; x <= column + ROOM_LENGTH; x++) {
				for (int z = row - 5; z <= row + 5; z++) {
					level.setBlock(new BlockPos(x, standing - 1, z), Blocks.STONE.defaultBlockState(), 3);
					for (int dy = 0; dy < ROOM_HEIGHT; dy++) {
						level.setBlock(new BlockPos(x, standing + dy, z), Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
			int[] distances = {4, 8, 14, 20, 28, 38, 50};
			for (int i = 0; i < distances.length; i++) {
				BlockPos base = BlockPos.containing(X + distances[i], standing, Z + (i % 2 == 0 ? -2 : 2));
				for (int dy = 0; dy < 4; dy++) {
					level.setBlock(base.above(dy), Blocks.QUARTZ_BLOCK.defaultBlockState(), 3);
				}
			}
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			// Yaw -90 faces east, towards the pillars.
			player.teleportTo(level, X, standing, Z, Set.of(), -90, 0, true);
		});
		context.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(layer)));
		context.waitTicks(60);
	}
}
