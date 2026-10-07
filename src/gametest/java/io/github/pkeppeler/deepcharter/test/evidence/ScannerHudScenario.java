package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.test.ScannerHudTest;

/**
 * Evidence scenario "scanner-hud": the scanner on the surface at midnight, ore appearing in the
 * map as it is placed, then the same in the dark of layer 2.
 */
public class ScannerHudScenario extends EvidenceScenario {
	private static final int FRAMES_PER_STEP = 6;
	private static final int TICKS_PER_FRAME = 3;

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

			ScannerHudTest.rideWithGoldAhead(context, server);
			screenshot(context, "scanner-surface-midnight");
			frames(context);
			// The flat surface world has only 4 blocks of ground under the pod.
			addOre(context, server, -2, -3);

			ScannerHudTest.leavePod(context, server);
			ScannerHudTest.goToLayer(server, 2);
			context.waitFor(client -> client.level.dimension().identifier().getPath().equals("layer_2"));
			context.waitTicks(40);
			ScannerHudTest.rideWithGoldAhead(context, server);
			screenshot(context, "scanner-layer-2");
			frames(context);
			addOre(context, server, -12, -20);
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
