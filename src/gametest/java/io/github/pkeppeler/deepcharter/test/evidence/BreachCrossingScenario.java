package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.layer.LayerChain;

/**
 * Evidence scenario "breach-crossing": a recording of a player falling through a breach, with the
 * fade and the transmission, plus screenshots of the same view in layer 1 and layer 2 to compare
 * their darkness and fog.
 */
public class BreachCrossingScenario extends EvidenceScenario {
	private static final double X = 2000.5;
	private static final double Z = 2000.5;
	/** Layer 1's stone ends at y 102 and layer 2's at y 132 (see the test world preset). */
	private static final int[] STANDING_Y = {103, 133};
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
			singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().setPermanentlyInvulnerable(true));

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
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.teleportTo(one, X, SHAFT_TOP - 1, Z, Set.of(), 0, 70, true);
			});
			context.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(1)));

			int typedAt = -1;
			for (int tick = 0; tick < PATIENCE; tick++) {
				frame(context);
				boolean typed = context.computeOnClient(client -> !BreachEffects.transmissionFull().isEmpty()
						&& BreachEffects.transmissionShown().equals(BreachEffects.transmissionFull()));
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
			int standing = STANDING_Y[layer - 1];
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
