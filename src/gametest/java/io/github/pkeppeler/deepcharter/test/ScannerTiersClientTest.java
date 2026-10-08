package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest.HudShot;

/**
 * Client GameTest for #74: the scanner HUD is hidden with no scanner and shown with one, and a tier 2 panel
 * reaches ore and rock that a tier 1 panel does not.
 */
public class ScannerTiersClientTest implements FabricClientGameTest {
	private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
	private static final int RGB = 0xFFFFFF;
	/** Beyond tier 1's 24 blocks each side, inside tier 2's 36. */
	public static final int FAR_AHEAD = 30;
	public static final int FAR_BEHIND = -30;
	private static final ScanArea TIER_TWO = TUNING.area(2);

	/** Puts gold {@link #FAR_AHEAD} ahead of the ridden pod and stone {@link #FAR_BEHIND} behind it, on the gold row. */
	public static void placeFarCells(ClientGameTestContext context, TestServerContext server) {
		ScannerHudTest.placeBesidePod(server, FAR_AHEAD, ScannerHudTest.GOLD_UP, Blocks.GOLD_ORE);
		ScannerHudTest.placeBesidePod(server, FAR_BEHIND, ScannerHudTest.GOLD_UP, Blocks.STONE);
		context.waitTicks(2 * TUNING.rescanTicks() + 2);
	}

	private static void expect(String label, int actual, int colour) {
		if (actual != (colour & RGB)) {
			throw new AssertionError("%s: the pixel should be %06X, was %06X".formatted(label, colour & RGB, actual));
		}
	}

	private static void expectNot(String label, int actual, int colour) {
		if (actual == (colour & RGB)) {
			throw new AssertionError("%s: the pixel must not be %06X".formatted(label, colour & RGB));
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			server.runCommand("time set midnight");
			context.waitTicks(20);

			// No scanner: no HUD, whatever is around the pod.
			ScannerHudTest.rideWithGoldAhead(context, server, 0);
			HudShot none = HudShot.take(context, "scanner-tiers-none", ScannerHudTest.TIER_ONE);
			expectNot("no scanner: the pod marker", none.pixel(0, 0), TUNING.podColor());
			expectNot("no scanner: the gold", none.pixel(ScannerHudTest.GOLD_AHEAD, ScannerHudTest.GOLD_UP), TUNING.goldOreColor());
			ScannerHudTest.leavePod(context, server);

			// Tier 1 draws M1's panel: the near gold is there, the far cells are outside it.
			ScannerHudTest.rideWithGoldAhead(context, server, 1);
			placeFarCells(context, server);
			HudShot one = HudShot.take(context, "scanner-tiers-1", ScannerHudTest.TIER_ONE);
			expect("tier 1: the pod marker", one.pixel(0, 0), TUNING.podColor());
			expect("tier 1: the near gold", one.pixel(ScannerHudTest.GOLD_AHEAD, ScannerHudTest.GOLD_UP), TUNING.goldOreColor());
			// The far gold is beyond the panel's columns, so the near gold is the only gold cell on the row.
			int goldCells = 0;
			for (int ahead = -ScannerHudTest.TIER_ONE.halfWidth(); ahead <= ScannerHudTest.TIER_ONE.halfWidth(); ahead++) {
				if (one.pixel(ahead, ScannerHudTest.GOLD_UP) == (TUNING.goldOreColor() & RGB)) {
					goldCells++;
				}
			}
			if (goldCells != 1) {
				throw new AssertionError("tier 1: the gold row should show the near gold only, it shows %d gold cells".formatted(goldCells));
			}
			ScannerHudTest.leavePod(context, server);

			// Tier 2 draws a wider panel with the far cells in it.
			ScannerHudTest.rideWithGoldAhead(context, server, 2);
			placeFarCells(context, server);
			HudShot two = HudShot.take(context, "scanner-tiers-2", TIER_TWO);
			expect("tier 2: the pod marker", two.pixel(0, 0), TUNING.podColor());
			expect("tier 2: the near gold", two.pixel(ScannerHudTest.GOLD_AHEAD, ScannerHudTest.GOLD_UP), TUNING.goldOreColor());
			expect("tier 2: the far gold", two.pixel(FAR_AHEAD, ScannerHudTest.GOLD_UP), TUNING.goldOreColor());
			expect("tier 2: the far stone", two.pixel(FAR_BEHIND, ScannerHudTest.GOLD_UP), TUNING.rockColor());
		}
	}
}
