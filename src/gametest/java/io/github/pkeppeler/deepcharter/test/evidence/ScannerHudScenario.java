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
 * Evidence scenario "scanner-hud": the scanner on the surface at midnight, ore appearing in the
 * map as it is placed, then the same in the dark of layer 2. There, lava in the rock shows as open space on a tier 1 scanner and
 * in its own colour on a tier 2 one (#300).
 */
public class ScannerHudScenario extends EvidenceScenario {
	private static final int FRAMES_PER_STEP = 6;
	private static final int TICKS_PER_FRAME = 3;
	private static final int LAVA_SHOT_TICKS = 100;
	/** Lava at (ahead, up), down in the rock and inside both tiers' reach. */
	private static final int[][] LAVA = {{3, -9}, {4, -9}, {4, -10}, {-8, -15}, {-7, -15}};

	@Override
	protected String name() {
		return "scanner-hud";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			server.runCommand("time set midnight");
			context.waitTicks(40);

			ScannerHudTest.rideWithGoldAhead(context, server, 1);
			screenshot(context, "scanner-surface-midnight");
			frames(context);
			// The flat surface world has only 4 blocks of ground under the pod.
			addOre(context, server, -2, -3);

			ScannerHudTest.leavePod(context, server);
			ScannerHudTest.goToLayer(server, 2);
			context.waitFor(client -> client.level.dimension().identifier().getPath().equals("layer_2"));
			context.waitTicks(40);
			RoomCarver.carveAroundFirstPlayer(server);
			ScannerHudTest.rideWithGoldAhead(context, server, 1);
			screenshot(context, "scanner-layer-2");
			frames(context);
			addOre(context, server, -12, -20);

			// #300: lava in the rock below. A tier 1 scanner draws it as open space; a tier 2 scanner marks it in its own colour.
			// The charter's transmission has played over the first tier 1 map by now, so the two lava shots are clear of it.
			placeLava(server, Blocks.LAVA);
			ScannerHudTest.leavePod(context, server);
			mountAndShoot(context, server, 2, "scanner-layer-2-lava-tier-2");
			frames(context);
			ScannerHudTest.leavePod(context, server);
			mountAndShoot(context, server, 1, "scanner-layer-2-lava-tier-1");
			// Seal the lava in stone again so that it has no time to flow.
			placeLava(server, Blocks.STONE);
		}
	}

	private void mountAndShoot(ClientGameTestContext context, TestServerContext server, int scannerTier, String screenshotName) {
		ScannerHudTest.mountFirstPlayer(server, scannerTier);
		context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
		// A transmission can play over the map just after a mount; the shot waits it out. The pod stands in lava and burns, but 100 hull outlasts the wait.
		context.waitTicks(LAVA_SHOT_TICKS);
		screenshot(context, screenshotName);
	}

	private static void placeLava(TestServerContext server, Block block) {
		for (int[] cell : LAVA) {
			ScannerHudTest.placeBesidePod(server, cell[0], cell[1], block);
		}
	}

	/** Diamond, then two iron, appear in the map a few ticks after they are placed. */
	private void addOre(ClientGameTestContext context, TestServerContext server, int diamondUp, int ironUp) {
		ScannerHudTest.placeBesidePod(server, -9, diamondUp, Blocks.DIAMOND_ORE);
		frames(context);
		ScannerHudTest.placeBesidePod(server, 12, ironUp, Blocks.IRON_ORE);
		ScannerHudTest.placeBesidePod(server, 13, ironUp, Blocks.IRON_ORE);
		frames(context);
	}

	private void frames(ClientGameTestContext context) {
		for (int i = 0; i < FRAMES_PER_STEP; i++) {
			frame(context);
			context.waitTicks(TICKS_PER_FRAME);
		}
	}
}
