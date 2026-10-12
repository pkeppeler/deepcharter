package io.github.pkeppeler.deepcharter.test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest.HudShot;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * #537: the scanner draws each known cell as the block's own texture, scaled down to the cell, from the active resource pack. A row of
 * different blocks reads as different cells that keep their grain; a pack that restyles stone restyles its cell; a stone with no usable
 * sprite falls back to the theme's flat rock colour.
 */
public class ScannerTexturesClientTest implements FabricClientGameTest {
	private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
	private static final int RGB = 0xFFFFFF;
	private static final int ROW = ScannerHudTest.GOLD_UP;
	/** The blocks of the row, one cell each, from {@link #FIRST} ahead. Stone is the first. */
	private static final List<Block> BLOCKS = List.of(Blocks.STONE, Blocks.DEEPSLATE, HazardBlocks.COMPANY_ROCK, Blocks.IRON_ORE,
			Blocks.GOLD_ORE, Blocks.DIAMOND_ORE, Blocks.COAL_ORE);
	private static final double MIN_ORE_RATIO = 2.0;
	private static final int FIRST = 1;
	/** Index in {@link #BLOCKS} of the first ore. */
	private static final int FIRST_ORE = 3;

	private static HudShot shot(ClientGameTestContext context, String name) {
		return HudShot.take(context, name, ScannerHudTest.TIER_ONE);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			server.runCommand("time set midnight");
			context.waitTicks(20);
			ScannerHudTest.rideWithGoldAhead(context, server, 1);
			for (int i = 0; i < BLOCKS.size(); i++) {
				ScannerHudTest.placeBesidePod(server, FIRST + i, ROW, BLOCKS.get(i));
			}
			context.waitTicks(2 * TUNING.rescanTicks() + 2);

			// Each block is its own texture, and a texture keeps its grain: more than one colour in a cell, not their average.
			HudShot textured = shot(context, "scanner-textures");
			Set<List<Integer>> seen = new HashSet<>();
			for (int i = 0; i < BLOCKS.size(); i++) {
				int[] pixels = textured.cellPixels(FIRST + i, ROW);
				long colours = Arrays.stream(pixels).distinct().count();
				require(colours > 1, "%s: the cell should keep the block's grain, but it is one flat colour (%06X)".formatted(BLOCKS.get(i), pixels[0]));
				require(seen.add(Arrays.stream(pixels).boxed().toList()), "%s: the cell should look unlike the other blocks' cells".formatted(BLOCKS.get(i)));
			}
			// Ore stays easy to spot: the frame and lift make every ore cell at least MIN_ORE_RATIO times as bright as the rock beside it. A
			// flat ore colour was 2.8 times as bright as the flat rock, but it was also all there was of the cell; the frame carries the ore
			// and leaves the texture inside it to show.
			double brightestRock = Math.max(textured.brightness(FIRST, ROW), textured.brightness(FIRST + 1, ROW));
			for (int i = FIRST_ORE; i < BLOCKS.size(); i++) {
				double ratio = textured.brightness(FIRST + i, ROW) / brightestRock;
				require(ratio >= MIN_ORE_RATIO, "%s: the ore cell should be %.1f times as bright as the rock, it is %.2f".formatted(BLOCKS.get(i), MIN_ORE_RATIO, ratio));
			}
			// The frame is there on a cell big enough for it: the ore cell's corner pixel is the frame colour, laid over the texture.
			int[] ore = textured.cellPixels(FIRST + FIRST_ORE, ROW);
			require((ore[0] & RGB) == (ScannerLook.current().oreOutlineColor() & RGB), "the iron ore cell should be framed in %06X, its corner is %06X"
					.formatted(ScannerLook.current().oreOutlineColor() & RGB, ore[0]));

			// The active pack's texture drives the cell: stone as flat magenta draws magenta.
			ClientPacks.enable(context, TestPacks.SCANNER_STONE_MAGENTA);
			try {
				context.waitTicks(2 * TUNING.rescanTicks() + 2);
				HudShot magenta = shot(context, "scanner-textures-magenta");
				require(magenta.channel(FIRST, ROW, 16) > 3 * magenta.channel(FIRST, ROW, 8) && magenta.channel(FIRST, ROW, 0) > 3 * magenta.channel(FIRST, ROW, 8),
						"the pack's magenta stone should draw magenta in the cell, red %.1f green %.1f blue %.1f".formatted(
								magenta.channel(FIRST, ROW, 16), magenta.channel(FIRST, ROW, 8), magenta.channel(FIRST, ROW, 0)));
				require(!Arrays.equals(magenta.cellPixels(FIRST + 1, ROW), magenta.cellPixels(FIRST, ROW)), "the deepslate cell should not change with the stone pack");
			} finally {
				ClientPacks.disable(context, TestPacks.SCANNER_STONE_MAGENTA);
			}

			// A stone with no usable sprite is the theme's flat rock colour.
			ClientPacks.enable(context, TestPacks.SCANNER_STONE_MISSING);
			try {
				context.waitTicks(2 * TUNING.rescanTicks() + 2);
				HudShot missing = shot(context, "scanner-textures-missing");
				int rock = ScannerLook.current().rockColor() & RGB;
				require(Arrays.stream(missing.cellPixels(FIRST, ROW)).allMatch(pixel -> pixel == rock),
						"a stone with no usable sprite should fall back to the flat rock colour %06X".formatted(rock));
			} finally {
				ClientPacks.disable(context, TestPacks.SCANNER_STONE_MISSING);
			}
		}
	}
}
