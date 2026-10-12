package io.github.pkeppeler.deepcharter.test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.scanner.ScannerHud;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/** Client GameTest: ore pixels in the scanner HUD. */
public class ScannerHudTest implements FabricClientGameTest {
	private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
	public static final ScanArea TIER_ONE = TUNING.area(1);
	private static final int RGB = 0xFFFFFF;
	/** How many brightness levels (of 255) an ore cell must be above a stone cell: the contrast the old flat colours gave was far above it. */
	private static final int MIN_ORE_LIFT = 20;

	/** The ore sits this many blocks ahead of the pod and this many below its feet; the facing is the pod's. */
	public static final int GOLD_AHEAD = 5;
	public static final int GOLD_UP = -4;

	/** Places {@code block} {@code ahead} blocks along the ridden pod's facing and {@code up} above its feet. */
	public static void placeBesidePod(TestServerContext server, int ahead, int up, Block block) {
		server.runOnServer(minecraftServer -> {
			PodEntity pod = (PodEntity) minecraftServer.getPlayerList().getPlayers().getFirst().getVehicle();
			place((ServerLevel) pod.level(), pod.blockPosition().relative(pod.getDirection(), ahead).above(up), block);
		});
	}

	private static void place(ServerLevel level, BlockPos at, Block block) {
		level.setBlock(at, block.defaultBlockState(), 3);
		if (!level.getBlockState(at).is(block)) {
			throw new AssertionError("could not place " + block + " at " + at + " in " + level.dimension().identifier());
		}
	}

	/** Mounts the first player on a new pod with a scanner of {@code scannerTier}, or with none for 0. */
	public static void mountFirstPlayer(TestServerContext server, int scannerTier) {
		server.runOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			PodEntity pod = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
			pod.setPos(player.position());
			player.level().addFreshEntity(pod);
			if (scannerTier > 0) {
				ScannerPods.fit(minecraftServer, player, pod, scannerTier);
			}
			if (!player.startRiding(pod)) {
				throw new AssertionError("the player could not mount the new pod");
			}
		});
	}

	/**
	 * Mounts the first player on a new pod with a scanner of {@code scannerTier} and builds a row of known cells around the gold ore: air
	 * ahead 4 and 6, gold ahead 5, stone ahead 7, all {@link #GOLD_UP} blocks below the pod's feet.
	 * Returns once the client has the ore in its chunk data and the HUD has had time to rescan.
	 */
	public static void rideWithGoldAhead(ClientGameTestContext context, TestServerContext server, int scannerTier) {
		mountFirstPlayer(server, scannerTier);
		ClientWait.until(context, "the client riding a pod", client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
		BlockPos gold = server.computeOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			PodEntity pod = (PodEntity) player.getVehicle();
			Direction facing = pod.getDirection();
			BlockPos feet = pod.blockPosition();
			BlockPos row = feet.above(GOLD_UP);
			place(player.level(), row.relative(facing, GOLD_AHEAD - 1), Blocks.AIR);
			place(player.level(), row.relative(facing, GOLD_AHEAD), Blocks.GOLD_ORE);
			place(player.level(), row.relative(facing, GOLD_AHEAD + 1), Blocks.AIR);
			place(player.level(), row.relative(facing, GOLD_AHEAD + 2), Blocks.STONE);
			return row.relative(facing, GOLD_AHEAD);
		});
		ClientWait.until(context, "the gold ore block", client -> client.level.getBlockState(gold).is(Blocks.GOLD_ORE));
		context.waitTicks(2 * TUNING.rescanTicks() + 2);
	}

	/** A screenshot of the HUD, read cell by cell. */
	public record HudShot(BufferedImage image, int guiWidth, int guiHeight, int scale, ScanArea area) {
		/** A screenshot read with the geometry of {@code area}, the area of the scanner the HUD is showing. */
		public static HudShot take(ClientGameTestContext context, String screenshotName, ScanArea area) {
			int[] window = context.computeOnClient(client -> new int[] {
					client.getWindow().getWidth(), client.getWindow().getGuiScaledWidth(),
					client.getWindow().getGuiScaledHeight(), client.getWindow().getGuiScale()});
			Path file = context.takeScreenshot(screenshotName);
			BufferedImage image;
			try {
				image = ImageIO.read(file.toFile());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			if (image.getWidth() != window[0]) {
				throw new AssertionError("the screenshot is " + image.getWidth() + " wide but the window is " + window[0]);
			}
			return new HudShot(image, window[1], window[2], window[3], area);
		}

		/** The RGB of the middle of the HUD cell {@code ahead} and {@code up} from the pod. */
		public int pixel(int ahead, int up) {
			int half = ScannerHud.cellSize(guiWidth, guiHeight, area) * scale / 2;
			int x = ScannerHud.cellLeft(guiWidth, guiHeight, area, ahead) * scale + half;
			int y = ScannerHud.cellTop(guiWidth, guiHeight, area, up) * scale + half;
			return image.getRGB(x, y) & RGB;
		}

		/** The RGB of every screen pixel of the HUD cell {@code ahead} and {@code up} from the pod. */
		public int[] cellPixels(int ahead, int up) {
			int size = ScannerHud.cellSize(guiWidth, guiHeight, area) * scale;
			int left = ScannerHud.cellLeft(guiWidth, guiHeight, area, ahead) * scale;
			int top = ScannerHud.cellTop(guiWidth, guiHeight, area, up) * scale;
			int[] pixels = new int[size * size];
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					pixels[y * size + x] = image.getRGB(left + x, top + y) & RGB;
				}
			}
			return pixels;
		}

		/** Whether every pixel of the cell is the colour of open space. */
		public boolean isOpenSpace(int ahead, int up) {
			int air = ScannerLook.current().airColor() & RGB;
			return Arrays.stream(cellPixels(ahead, up)).allMatch(pixel -> pixel == air);
		}

		/** Mean brightness (0 to 255) over the pixels of the cell. */
		public double brightness(int ahead, int up) {
			return Arrays.stream(cellPixels(ahead, up)).mapToDouble(HudShot::luma).average().orElseThrow();
		}

		/** Mean of one colour channel (shift 16 red, 8 green, 0 blue) over the pixels of the cell. */
		public double channel(int ahead, int up, int shift) {
			return Arrays.stream(cellPixels(ahead, up)).mapToDouble(pixel -> (pixel >> shift) & 0xFF).average().orElseThrow();
		}

		/** Brightness (0 to 255) of an RGB colour. */
		public static double luma(int rgb) {
			return 0.2126 * ((rgb >> 16) & 0xFF) + 0.7152 * ((rgb >> 8) & 0xFF) + 0.0722 * (rgb & 0xFF);
		}
	}

	/**
	 * The gold row as the scanner draws it: the cells are the blocks' own textures, so a cell is checked by what it shows and not by one
	 * colour. Open space is the flat air colour; stone and ore are not; and the ore is brighter than the stone beside it, which is what
	 * keeps ore as easy to spot as its flat colour was.
	 */
	private static void expectGoldRow(ClientGameTestContext context, String label) {
		HudShot shot = HudShot.take(context, label, TIER_ONE);
		require(shot.isOpenSpace(GOLD_AHEAD - 1, GOLD_UP), label + ": the cell before the gold should be open space");
		require(shot.isOpenSpace(GOLD_AHEAD + 1, GOLD_UP), label + ": the cell after the gold should be open space");
		require(!shot.isOpenSpace(GOLD_AHEAD, GOLD_UP), label + ": the gold cell should draw the gold ore's texture");
		require(!shot.isOpenSpace(GOLD_AHEAD + 2, GOLD_UP), label + ": the stone cell should draw the stone texture");
		double gold = shot.brightness(GOLD_AHEAD, GOLD_UP);
		double stone = shot.brightness(GOLD_AHEAD + 2, GOLD_UP);
		require(gold > stone + MIN_ORE_LIFT, "%s: the gold cell (%.1f) should be at least %d brighter than the stone cell (%.1f)"
				.formatted(label, gold, MIN_ORE_LIFT, stone));
		require(shot.channel(GOLD_AHEAD, GOLD_UP, 16) > shot.channel(GOLD_AHEAD, GOLD_UP, 0), label + ": the gold cell should read yellow, red over blue");
	}

	/** Puts the first player out of the pod and removes the pod. */
	public static void leavePod(ClientGameTestContext context, TestServerContext server) {
		server.runOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			PodEntity pod = (PodEntity) player.getVehicle();
			player.stopRiding();
			pod.discard();
		});
		ClientWait.until(context, "the player out of the pod", client -> client.player.getVehicle() == null);
	}

	private static void dismountAndExpectNoHud(ClientGameTestContext context, TestServerContext server, String label) {
		leavePod(context, server);
		context.waitTicks(2 * TUNING.rescanTicks() + 2);
		// The open cell next to the gold is the flat air colour in the panel, and a nine-pixel cell of that exact colour is not in the world.
		if (HudShot.take(context, label + "-dismounted", TIER_ONE).isOpenSpace(GOLD_AHEAD - 1, GOLD_UP)) {
			throw new AssertionError(label + ": the scanner HUD must not draw when the player is not riding a pod");
		}
	}

	/**
	 * Runs {@code /deepcharter layer goto n} as an op. The target chunk is generated first: the command
	 * reads the surface height from the heightmap, which is empty (the layer's min y) in a chunk
	 * that has not been generated yet, and the player would fall out of the world.
	 */
	public static void goToLayer(TestServerContext server, int n) {
		server.runOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			minecraftServer.getLevel(LayerChain.dimension(n)).getChunk(player.getBlockX() >> 4, player.getBlockZ() >> 4);
			try {
				minecraftServer.getCommands().getDispatcher().execute("deepcharter layer goto " + n,
						player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER));
			} catch (CommandSyntaxException e) {
				throw new AssertionError("could not go to layer " + n + ": " + e.getMessage(), e);
			}
		});
	}

	/** The whole panel, down to its bottom-right cell, stays on screen at small GUI sizes. */
	private static void expectPanelFitsSmallScreens() {
		int[][] guiSizes = {{427, 240}, {320, 180}, {240, 135}, {200, 100}};
		// Tiers 3 and up are taller than a small GUI: only tier 2 (the best a Mole takes) must fit the small ones.
		for (int tier = 1; tier <= 2; tier++) {
			ScanArea area = TUNING.area(tier);
			for (int[] size : guiSizes) {
				int width = size[0];
				int height = size[1];
				int cell = ScannerHud.cellSize(width, height, area);
				int right = ScannerHud.cellLeft(width, height, area, area.halfWidth()) + cell;
				int bottom = ScannerHud.cellTop(width, height, area, -area.down()) + cell;
				if (right > width || bottom > height || ScannerHud.cellLeft(width, height, area, -area.halfWidth()) < 0) {
					throw new AssertionError("tier %d at GUI %dx%d: the panel ends at (%d, %d) with %dpx cells: off screen"
							.formatted(tier, width, height, right, bottom, cell));
				}
			}
		}
		if (ScannerHud.cellSize(427, 240, TIER_ONE) != ScannerLook.current().cellPixels()) {
			throw new AssertionError("a roomy GUI should keep the tuned cell size " + ScannerLook.current().cellPixels());
		}
	}

	/**
	 * At the default GUI the panel's left edge stays right of the altimeter. It is centred at 213 and "-12,345 ft." is 66 pixels
	 * wide, so it ends at 246.
	 */
	private static void expectPanelClearsAltimeter() {
		int altimeterRight = 246;
		for (int tier = 1; tier <= ComponentTrack.SCANNER.maxTier(); tier++) {
			ScanArea area = TUNING.area(tier);
			int panelLeft = ScannerHud.cellLeft(427, 240, area, -area.halfWidth()) - 1;
			if (panelLeft <= altimeterRight) {
				throw new AssertionError("tier %d at GUI 427x240: the panel starts at x=%d, on the altimeter that ends at %d"
						.formatted(tier, panelLeft, altimeterRight));
			}
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		expectPanelFitsSmallScreens();
		expectPanelClearsAltimeter();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			server.runCommand("time set midnight");
			context.waitTicks(20);
			long time = server.computeOnServer(minecraftServer -> minecraftServer.overworld().getOverworldClockTime() % 24000);
			if (time < 17000 || time > 19000) {
				throw new AssertionError("the surface check must run at midnight, the day time is " + time);
			}
			rideWithGoldAhead(context, server, 1);
			expectGoldRow(context, "scanner-surface-midnight");
			dismountAndExpectNoHud(context, server, "scanner-surface-midnight");

			goToLayer(server, 2);
			ClientWait.until(context, "the client in layer_2", client -> client.level.dimension().identifier().getPath().equals("layer_2"));
			context.waitTicks(40);
			rideWithGoldAhead(context, server, 1);
			expectGoldRow(context, "scanner-layer-2");
		}
	}
}
