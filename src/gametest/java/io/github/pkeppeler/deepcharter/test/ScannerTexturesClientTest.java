package io.github.pkeppeler.deepcharter.test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest.HudShot;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.OreBlocks;
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
	/** The blocks of the row, one cell each, from {@link #FIRST} ahead: the layers' own rock first, then Company Rock, then four of the mod's ores. */
	private static final List<Block> BLOCKS = List.of(block("packed_shale"), block("green_shale"), HazardBlocks.COMPANY_ROCK,
			OreBlocks.of(OreType.IRONIUM), OreBlocks.of(OreType.BRONZIUM), OreBlocks.of(OreType.SILVERIUM), OreBlocks.of(OreType.GOLDIUM));
	private static final int FIRST = 1;
	/** A plain vanilla stone cell after the row: the test packs restyle stone. */
	private static final int STONE_AHEAD = FIRST + BLOCKS.size();

	private static Block block(String path) {
		return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
	}

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
			ScannerHudTest.placeBesidePod(server, STONE_AHEAD, ROW, Blocks.STONE);
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
			// The active pack's texture drives the cell: stone as flat magenta draws magenta.
			ClientPacks.enable(context, TestPacks.SCANNER_STONE_MAGENTA);
			try {
				context.waitTicks(2 * TUNING.rescanTicks() + 2);
				HudShot magenta = shot(context, "scanner-textures-magenta");
				require(magenta.channel(STONE_AHEAD, ROW, 16) > 3 * magenta.channel(STONE_AHEAD, ROW, 8) && magenta.channel(STONE_AHEAD, ROW, 0) > 3 * magenta.channel(STONE_AHEAD, ROW, 8),
						"the pack's magenta stone should draw magenta in the cell, red %.1f green %.1f blue %.1f".formatted(
								magenta.channel(STONE_AHEAD, ROW, 16), magenta.channel(STONE_AHEAD, ROW, 8), magenta.channel(STONE_AHEAD, ROW, 0)));
				require(Arrays.equals(magenta.cellPixels(FIRST, ROW), textured.cellPixels(FIRST, ROW)), "the shale cell should not change with the stone pack");
			} finally {
				ClientPacks.disable(context, TestPacks.SCANNER_STONE_MAGENTA);
			}

			// A stone with no usable sprite is the theme's flat rock colour.
			ClientPacks.enable(context, TestPacks.SCANNER_STONE_MISSING);
			try {
				context.waitTicks(2 * TUNING.rescanTicks() + 2);
				HudShot missing = shot(context, "scanner-textures-missing");
				int flatRock = ScannerLook.current().rockColor() & RGB;
				require(Arrays.stream(missing.cellPixels(STONE_AHEAD, ROW)).allMatch(pixel -> pixel == flatRock),
						"a stone with no usable sprite should fall back to the flat rock colour %06X".formatted(flatRock));
			} finally {
				ClientPacks.disable(context, TestPacks.SCANNER_STONE_MISSING);
			}
		}
	}
}
