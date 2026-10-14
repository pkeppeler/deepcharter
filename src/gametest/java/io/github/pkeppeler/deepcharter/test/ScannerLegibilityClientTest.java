package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest.HudShot;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.OreBlocks;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * #537: ore stays easy to spot on the scanner map, measured at the size the cells have on screen and not on the unscaled tile. Each ore of a row
 * (Ironium, Bronzium, Silverium, Goldium) sits between rock and lava. The ore cell is compared with the rock and lava cells round it,
 * once with large cells (a tier 1 scanner in the cockpit window; lava reads as open space there, so only rock is the background) and once with the smallest
 * the game draws (a tier 2 scanner: 2 screen pixels a cell, lava in the background). The baseline is the flat ore colour that main drew, against the flat rock and lava
 * colours it drew: the theme keeps all three.
 */
public class ScannerLegibilityClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("ScannerLegibility");
	private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
	private static final int RGB = 0xFFFFFF;
	private static final int ROW = ScannerHudTest.GOLD_UP;
	private static final int FIRST = 1;
	/** How far (RGB distance, of 441) a pixel must be from a background cell's mean colour to stand out of it. */
	private static final double CONTRAST = 48;
	/** The ore's mean may be this share of the old flat colour's distance from the background: a mark tinted to tell its ore apart is a little nearer to the lava than white was. */
	private static final double SEPARATION_FLOOR = 0.8;
	private static final Block SHALE = block("packed_shale");
	/** One cell each from {@link #FIRST} ahead; every ore has rock or lava on both sides. {@code null} is lava. */
	private static final List<Block> BLOCKS = Arrays.asList(SHALE, null, OreBlocks.of(OreType.IRONIUM), SHALE, OreBlocks.of(OreType.BRONZIUM), null,
			OreBlocks.of(OreType.SILVERIUM), SHALE, OreBlocks.of(OreType.GOLDIUM), SHALE);

	private static Block block(String path) {
		return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
	}

	private static boolean isOre(Block block) {
		return block != null && block != SHALE;
	}

	private static double[] mean(int[] pixels) {
		double[] mean = new double[3];
		for (int pixel : pixels) {
			mean[0] += (pixel >> 16) & 0xFF;
			mean[1] += (pixel >> 8) & 0xFF;
			mean[2] += pixel & 0xFF;
		}
		for (int i = 0; i < 3; i++) {
			mean[i] /= pixels.length;
		}
		return mean;
	}

	private static double distance(double[] a, double[] b) {
		return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
	}

	private static double[] rgb(int colour) {
		return new double[] {(colour >> 16) & 0xFF, (colour >> 8) & 0xFF, colour & 0xFF};
	}

	/** How well an ore cell stands out of the cells round it. */
	private record Legibility(double share, double separation) {
		/** {@code share}: the share of the ore pixels that are {@link #CONTRAST} or more from the mean of every background cell. {@code separation}: the distance of the ore's mean from the nearest background mean. */
		static Legibility of(int[] ore, List<double[]> backgrounds) {
			long far = Arrays.stream(ore).filter(pixel -> backgrounds.stream().allMatch(bg -> distance(rgb(pixel), bg) >= CONTRAST)).count();
			double nearest = backgrounds.stream().mapToDouble(bg -> distance(mean(ore), bg)).min().orElseThrow();
			return new Legibility((double) far / ore.length, nearest);
		}
	}

	/** Mounts the first player on a new pod with a scanner of {@code tier} and builds the row round it, the lava in stone so that it stays where it is. */
	private static void buildRow(ClientGameTestContext context, TestServerContext server, int tier) {
		ScannerHudTest.mountFirstPlayer(server, tier);
		ClientWait.until(context, "the client riding a pod", client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
		List<BlockPos> positions = server.computeOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			PodEntity pod = (PodEntity) player.getVehicle();
			ServerLevel level = (ServerLevel) pod.level();
			Direction facing = pod.getDirection();
			BlockPos row = pod.blockPosition().above(ROW);
			List<BlockPos> placed = new ArrayList<>();
			for (int i = 0; i < BLOCKS.size(); i++) {
				BlockPos at = row.relative(facing, FIRST + i);
				for (Direction around : new Direction[] {Direction.UP, Direction.DOWN, facing.getClockWise(), facing.getCounterClockWise()}) {
					level.setBlock(at.relative(around), Blocks.STONE.defaultBlockState(), 3);
				}
				level.setBlock(at, (BLOCKS.get(i) == null ? Blocks.LAVA : BLOCKS.get(i)).defaultBlockState(), 3);
				placed.add(at);
			}
			return placed;
		});
		ClientWait.until(context, "the row of blocks", client -> positions.stream().allMatch(at -> !client.level.getBlockState(at).isAir()));
		context.waitTicks(2 * TUNING.rescanTicks() + 2);
	}

	/** Measures each ore of the row against its background and requires it to be as legible as the old flat colour was; returns the side of a cell in screen pixels. */
	private static int measure(ClientGameTestContext context, String label, ScanArea area, boolean lavaShows) {
		ScannerLook look = ScannerLook.current();
		HudShot shot = HudShot.take(context, label, area);
		int size = (int) Math.round(Math.sqrt(shot.cellPixels(FIRST, ROW).length));
		List<double[]> backgrounds = new ArrayList<>();
		List<double[]> flatBackgrounds = new ArrayList<>();
		flatBackgrounds.add(rgb(look.rockColor() & RGB));
		if (lavaShows) {
			flatBackgrounds.add(rgb(look.lavaColor() & RGB));
		}
		for (int i = 0; i < BLOCKS.size(); i++) {
			if (!isOre(BLOCKS.get(i)) && (BLOCKS.get(i) != null || lavaShows)) {
				backgrounds.add(mean(shot.cellPixels(FIRST + i, ROW)));
			}
		}
		for (int i = 0; i < BLOCKS.size(); i++) {
			if (!isOre(BLOCKS.get(i))) {
				continue;
			}
			Legibility now = Legibility.of(shot.cellPixels(FIRST + i, ROW), backgrounds);
			boolean gold = BLOCKS.get(i) == OreBlocks.of(OreType.GOLDIUM);
			int[] flat = new int[size * size];
			Arrays.fill(flat, (gold ? look.goldOreColor() : look.oreColor()) & RGB);
			Legibility old = Legibility.of(flat, flatBackgrounds);
			LOGGER.info("[scanner-legibility] {} {}px cells {}: share {} (old {}), separation {} (old {})", label, size, BLOCKS.get(i), now.share(), old.share(), now.separation(), old.separation());
			require(now.share() >= old.share(), "%s (%dpx cells) %s: %.0f%% of its pixels stand out of the rock and lava round it, the old flat colour's %.0f%%".formatted(label, size, BLOCKS.get(i), now.share() * 100, old.share() * 100));
			// A textured ore keeps its grain, so its mean is nearer the rock than a flat white was; only a flat mark is held to the old distance.
			require(size >= look.oreMarkBelowPixels() || now.separation() >= SEPARATION_FLOOR * old.separation(), "%s (%dpx cells) %s: its mean is %.0f from the nearest rock or lava cell, the old flat colour's %.0f".formatted(label, size, BLOCKS.get(i), now.separation(), old.separation()));
		}
		return size;
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		int markBelow = ScannerLook.current().oreMarkBelowPixels();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			server.runCommand("time set midnight");
			context.waitTicks(20);
			buildRow(context, server, 1);
			int large = measure(context, "scanner-legibility-large", TUNING.area(1), false);
			require(large >= markBelow, "the large case should have cells of %d screen pixels or more, it has %d".formatted(markBelow, large));
			ScannerHudTest.leavePod(context, server);
			buildRow(context, server, 2);
			int small = measure(context, "scanner-legibility-small", TUNING.area(2), true);
			require(small < markBelow, "the small case should have cells under %d screen pixels, it has %d".formatted(markBelow, small));
		}
	}
}
