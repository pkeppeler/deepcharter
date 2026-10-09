package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest.HudShot;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

/**
 * Client GameTest for #74: the scanner HUD is hidden with no scanner and shown with one, and a tier 2 panel
 * reaches ore and rock that a tier 1 panel does not. For #300 a tier 2 panel also marks lava in its own colour, where tier 1 draws lava
 * and water as open space.
 */
public class ScannerTiersClientTest implements FabricClientGameTest {
	private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
	private static final int RGB = 0xFFFFFF;
	/** Beyond tier 1's 24 blocks each side, inside tier 2's 36. */
	public static final int FAR_AHEAD = 30;
	public static final int FAR_BEHIND = -30;
	private static final ScanArea TIER_TWO = TUNING.area(2);
	/** Lava ahead and water behind, on a row of their own, so that water spreading in the wait cannot reach the lava. */
	private static final int FLUID_UP = -2;
	private static final int LAVA_AHEAD = 3;
	private static final int WATER_AHEAD = -6;
	/** Lava one block beside the plane, with air in the plane at that cell: the thermal tier draws it as near lava. */
	private static final int NEAR_AHEAD = 8;

	/** Puts gold {@link #FAR_AHEAD} ahead of the ridden pod and stone {@link #FAR_BEHIND} behind it, on the gold row. */
	public static void placeFarCells(ClientGameTestContext context, TestServerContext server) {
		ScannerHudTest.placeBesidePod(server, FAR_AHEAD, ScannerHudTest.GOLD_UP, Blocks.GOLD_ORE);
		ScannerHudTest.placeBesidePod(server, FAR_BEHIND, ScannerHudTest.GOLD_UP, Blocks.STONE);
		context.waitTicks(2 * TUNING.rescanTicks() + 2);
	}

	/** Puts a lava source {@link #LAVA_AHEAD} ahead of the ridden pod and a water source {@link #WATER_AHEAD} ahead of it, on the fluid row. */
	public static void placeFluids(ClientGameTestContext context, TestServerContext server) {
		ScannerHudTest.placeBesidePod(server, LAVA_AHEAD, FLUID_UP, Blocks.LAVA);
		ScannerHudTest.placeBesidePod(server, WATER_AHEAD, FLUID_UP, Blocks.WATER);
		ScannerHudTest.placeBesidePod(server, NEAR_AHEAD, FLUID_UP, Blocks.AIR);
		server.runOnServer(minecraftServer -> {
			PodEntity pod = (PodEntity) minecraftServer.getPlayerList().getPlayers().getFirst().getVehicle();
			BlockPos beside = pod.blockPosition().relative(pod.getDirection(), NEAR_AHEAD).above(FLUID_UP).relative(pod.getDirection().getClockWise());
			pod.level().setBlock(beside, Blocks.LAVA.defaultBlockState(), 3);
		});
		BlockPos lava = server.computeOnServer(minecraftServer -> {
			PodEntity pod = (PodEntity) minecraftServer.getPlayerList().getPlayers().getFirst().getVehicle();
			return pod.blockPosition().relative(pod.getDirection(), LAVA_AHEAD).above(FLUID_UP);
		});
		ClientWait.until(context, "the lava source", client -> client.level.getBlockState(lava).is(Blocks.LAVA),
				client -> String.valueOf(client.level.getBlockState(lava)));
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
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			server.runCommand("time set midnight");
			context.waitTicks(20);

			// No scanner: no HUD, whatever is around the pod.
			ScannerHudTest.rideWithGoldAhead(context, server, 0);
			HudShot none = HudShot.take(context, "scanner-tiers-none", ScannerHudTest.TIER_ONE);
			expectNot("no scanner: the pod marker", none.pixel(0, 0), ScannerLook.current().podColor());
			expectNot("no scanner: the gold", none.pixel(ScannerHudTest.GOLD_AHEAD, ScannerHudTest.GOLD_UP), ScannerLook.current().goldOreColor());
			ScannerHudTest.leavePod(context, server);

			// Tier 1 draws M1's panel: the near gold is there, the far cells are outside it.
			ScannerHudTest.rideWithGoldAhead(context, server, 1);
			placeFarCells(context, server);
			HudShot one = HudShot.take(context, "scanner-tiers-1", ScannerHudTest.TIER_ONE);
			expect("tier 1: the pod marker", one.pixel(0, 0), ScannerLook.current().podColor());
			expect("tier 1: the near gold", one.pixel(ScannerHudTest.GOLD_AHEAD, ScannerHudTest.GOLD_UP), ScannerLook.current().goldOreColor());
			// The far gold is beyond the panel's columns, so the near gold is the only gold cell on the row.
			int goldCells = 0;
			for (int ahead = -ScannerHudTest.TIER_ONE.halfWidth(); ahead <= ScannerHudTest.TIER_ONE.halfWidth(); ahead++) {
				if (one.pixel(ahead, ScannerHudTest.GOLD_UP) == (ScannerLook.current().goldOreColor() & RGB)) {
					goldCells++;
				}
			}
			if (goldCells != 1) {
				throw new AssertionError("tier 1: the gold row should show the near gold only, it shows %d gold cells".formatted(goldCells));
			}
			// Tier 1 draws lava and water as open space, the colour of air.
			placeFluids(context, server);
			HudShot oneFluids = HudShot.take(context, "scanner-tiers-1-fluids", ScannerHudTest.TIER_ONE);
			expect("tier 1: the lava reads as open space", oneFluids.pixel(LAVA_AHEAD, FLUID_UP), ScannerLook.current().airColor());
			expect("tier 1: the water reads as open space", oneFluids.pixel(WATER_AHEAD, FLUID_UP), ScannerLook.current().airColor());
			expect("tier 1: the lava beside the plane reads as open space", oneFluids.pixel(NEAR_AHEAD, FLUID_UP), ScannerLook.current().airColor());
			ScannerHudTest.leavePod(context, server);

			// Tier 2 draws a wider panel with the far cells in it.
			ScannerHudTest.rideWithGoldAhead(context, server, 2);
			placeFarCells(context, server);
			HudShot two = HudShot.take(context, "scanner-tiers-2", TIER_TWO);
			expect("tier 2: the pod marker", two.pixel(0, 0), ScannerLook.current().podColor());
			expect("tier 2: the near gold", two.pixel(ScannerHudTest.GOLD_AHEAD, ScannerHudTest.GOLD_UP), ScannerLook.current().goldOreColor());
			expect("tier 2: the far gold", two.pixel(FAR_AHEAD, ScannerHudTest.GOLD_UP), ScannerLook.current().goldOreColor());
			expect("tier 2: the far stone", two.pixel(FAR_BEHIND, ScannerHudTest.GOLD_UP), ScannerLook.current().rockColor());

			// Tier 2 is the thermal tier: lava has its own colour, water is still open space.
			placeFluids(context, server);
			HudShot twoFluids = HudShot.take(context, "scanner-tiers-2-fluids", TIER_TWO);
			expect("tier 2: the lava in the plane", twoFluids.pixel(LAVA_AHEAD, FLUID_UP), ScannerLook.current().lavaColor());
			expect("tier 2: the lava beside the plane", twoFluids.pixel(NEAR_AHEAD, FLUID_UP), ScannerLook.current().lavaNearColor());
			expect("tier 2: the water reads as open space", twoFluids.pixel(WATER_AHEAD, FLUID_UP), ScannerLook.current().airColor());
		}
	}
}
