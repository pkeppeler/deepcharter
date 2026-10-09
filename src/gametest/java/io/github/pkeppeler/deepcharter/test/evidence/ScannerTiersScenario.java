package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;

/**
 * Evidence scenario "m2-scanner-tiers": in layer 2, the same pod with no scanner (no map), then a tier 1 scanner (M1's
 * range), then a tier 2 scanner (a wider and deeper map that reaches the ore a tier 1 scanner misses).
 */
public class ScannerTiersScenario extends EvidenceScenario {
	private static final int FRAMES_PER_TIER = 12;
	private static final int TICKS_PER_FRAME = 3;
	private static final int SETTLE_TICKS = 30;
	private static final int TRANSMISSION_TICKS = 600;

	/** Ore at (ahead, up), near the pod and so inside every tier. */
	private static final int[][] NEAR_ORE = {{6, -6}, {-10, -12}, {14, -20}};
	/** Ore beyond a tier 1 scanner's 24 blocks sideways or 32 down, inside tier 2's 36 and 48 (the shot crops a few cells off the right edge). */
	private static final int[][] FAR_ORE = {{26, -8}, {-31, -22}, {20, -42}, {-28, -45}};

	@Override
	protected String name() {
		return "m2-scanner-tiers";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			server.runCommand("time set midnight");
			context.waitTicks(40);
			ScannerHudTest.goToLayer(server, 2);
			context.waitFor(client -> client.level.dimension().identifier().getPath().equals("layer_2"));
			context.waitTicks(40);
			RoomCarver.carveAroundFirstPlayer(server);

			for (int tier = 0; tier <= 2; tier++) {
				ScannerHudTest.mountFirstPlayer(server, tier);
				context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
				if (tier == 1) {
					// Joining a charter plays its layer 2 transmission over the map; let it finish.
					context.waitTicks(TRANSMISSION_TICKS);
				}
				placeOre(server, NEAR_ORE, Blocks.GOLD_ORE);
				placeOre(server, FAR_ORE, Blocks.GOLD_ORE);
				context.waitTicks(SETTLE_TICKS);
				screenshot(context, "scanner-tier-" + tier);
				for (int i = 0; i < FRAMES_PER_TIER; i++) {
					frame(context);
					context.waitTicks(TICKS_PER_FRAME);
				}
				ScannerHudTest.leavePod(context, server);
			}
		}
	}

	private static void placeOre(TestServerContext server, int[][] cells, Block ore) {
		for (int[] cell : cells) {
			ScannerHudTest.placeBesidePod(server, cell[0], cell[1], ore);
		}
	}
}
