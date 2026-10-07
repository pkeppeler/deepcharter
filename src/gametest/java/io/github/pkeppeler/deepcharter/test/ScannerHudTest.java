package io.github.pkeppeler.deepcharter.test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.scanner.ScannerHud;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;

/** Client GameTest: ore pixels in the scanner HUD. */
public class ScannerHudTest implements FabricClientGameTest {
	private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
	private static final int RGB = 0xFFFFFF;

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

	/**
	 * Mounts the first player on a new pod and builds a row of known cells around the gold ore: air
	 * ahead 4 and 6, gold ahead 5, stone ahead 7, all {@link #GOLD_UP} blocks below the pod's feet.
	 * Returns once the client has the ore in its chunk data and the HUD has had time to rescan.
	 */
	public static void rideWithGoldAhead(ClientGameTestContext context, TestServerContext server) {
		PodShellClientTest.mountFirstPlayer(server);
		context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
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
		context.waitFor(client -> client.level.getBlockState(gold).is(Blocks.GOLD_ORE));
		context.waitTicks(2 * TUNING.rescanTicks() + 2);
	}

	/** A screenshot of the HUD, read cell by cell. */
	public record HudShot(BufferedImage image, int guiWidth, int scale) {
		/** Takes a screenshot of the current frame. */
		public static HudShot take(ClientGameTestContext context, String screenshotName) {
			int[] window = context.computeOnClient(client -> new int[] {
					client.getWindow().getWidth(), client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScale()});
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
			return new HudShot(image, window[1], window[2]);
		}

		/** The RGB of the middle of the HUD cell {@code ahead} and {@code up} from the pod. */
		public int pixel(int ahead, int up) {
			int half = TUNING.cellPixels() * scale / 2;
			int x = ScannerHud.cellLeft(guiWidth, ahead) * scale + half;
			int y = ScannerHud.cellTop(up) * scale + half;
			return image.getRGB(x, y) & RGB;
		}
	}

	private static void expectPixel(HudShot shot, String label, int ahead, int up, int colour) {
		int actual = shot.pixel(ahead, up);
		if (actual != (colour & RGB)) {
			throw new AssertionError("%s: the pixel in cell (ahead %d, up %d) should be %06X, was %06X"
					.formatted(label, ahead, up, colour & RGB, actual));
		}
	}

	private static void expectGoldRow(ClientGameTestContext context, String label) {
		HudShot shot = HudShot.take(context, label);
		expectPixel(shot, label + " gold", GOLD_AHEAD, GOLD_UP, TUNING.goldOreColor());
		expectPixel(shot, label + " air before", GOLD_AHEAD - 1, GOLD_UP, TUNING.airColor());
		expectPixel(shot, label + " air after", GOLD_AHEAD + 1, GOLD_UP, TUNING.airColor());
		expectPixel(shot, label + " rock", GOLD_AHEAD + 2, GOLD_UP, TUNING.rockColor());
	}

	/** Puts the first player out of the pod and removes the pod. */
	public static void leavePod(ClientGameTestContext context, TestServerContext server) {
		server.runOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			PodEntity pod = (PodEntity) player.getVehicle();
			player.stopRiding();
			pod.discard();
		});
		context.waitFor(client -> client.player.getVehicle() == null);
	}

	private static void dismountAndExpectNoHud(ClientGameTestContext context, TestServerContext server, String label) {
		leavePod(context, server);
		context.waitTicks(2 * TUNING.rescanTicks() + 2);
		int actual = HudShot.take(context, label + "-dismounted").pixel(GOLD_AHEAD, GOLD_UP);
		if (actual == (TUNING.goldOreColor() & RGB)) {
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

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			server.runCommand("time set midnight");
			context.waitTicks(20);
			long time = server.computeOnServer(minecraftServer -> minecraftServer.overworld().getOverworldClockTime() % 24000);
			if (time < 17000 || time > 19000) {
				throw new AssertionError("the surface check must run at midnight, the day time is " + time);
			}
			rideWithGoldAhead(context, server);
			expectGoldRow(context, "scanner-surface-midnight");
			dismountAndExpectNoHud(context, server, "scanner-surface-midnight");

			goToLayer(server, 2);
			context.waitFor(client -> client.level.dimension().identifier().getPath().equals("layer_2"));
			context.waitTicks(40);
			rideWithGoldAhead(context, server);
			expectGoldRow(context, "scanner-layer-2");
		}
	}
}
