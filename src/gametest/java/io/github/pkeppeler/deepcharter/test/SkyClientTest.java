package io.github.pkeppeler.deepcharter.test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import io.github.pkeppeler.deepcharter.test.support.ClientWait;

/**
 * Client GameTest for #239: what the player sees. Looking straight up, the sky is dusk when the sky clock is at its brightest and
 * near black at its darkest, whatever the gameplay clock says. Looking at the dusk sun, it is a small round disc with a narrow glow.
 */
public class SkyClientTest implements FabricClientGameTest {
	private static final long SKY_DARKEST = 144_000;
	/** Straight up, from high above the colony. */
	private static final String LOOK_UP = "tp @p 18 250 0 0 -90";
	/** At the dusk sun: the sun stands 10 degrees above the western horizon, and the view is turned 10 degrees off it so that the crosshair is clear. */
	private static final String LOOK_AT_SUN = "tp @p 18 250 0 100 -10";
	private static final int BRIGHT = 0xC0;
	/** A smooth disc fills pi/4 (0.785) of its bounding box and a pixelated one 0.9 or so, a square all of it. */
	private static final double ROUND_MIN = 0.70;
	private static final double ROUND_MAX = 0.94;
	/** The disc is under 100 pixels across at the 480 pixel window, vanilla's square sun twice that, and its glow stays within this of the middle. */
	private static final int SUN_MAX_WIDTH = 100;
	private static final int GLOW_MAX_RADIUS = 80;
	private static final int DUST_PIXELS = 6;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			ClientWait.until(context, "the client in the world", client -> client.player != null && client.level != null);
			TestServerContext server = singleplayer.getServer();
			server.runCommand("gamerule advance_time false");
			server.runCommand("time set noon");
			server.runCommand(LOOK_UP);

			server.runCommand("time of deepcharter:sky set 0");
			int[] dusk = skyAbove(context, "sky-dusk");
			// Dusk is warm and dim: red over green over blue, nowhere near vanilla's blue noon sky.
			if (!(dusk[0] > dusk[1] && dusk[1] > dusk[2] && dusk[0] >= 0x38 && dusk[2] <= 0x40)) {
				throw new AssertionError("The brightest sky should be the rust dusk (#4A2820), read (%d, %d, %d)".formatted(dusk[0], dusk[1], dusk[2]));
			}

			theSunIsSmallAndRound(context, server);

			server.runCommand(LOOK_UP);
			server.runCommand("time of deepcharter:sky set " + SKY_DARKEST);
			server.runCommand("time set midnight");
			int[] night = skyAbove(context, "sky-night");
			if (night[0] > 0x18 || night[1] > 0x18 || night[2] > 0x18) {
				throw new AssertionError("The darkest sky should be near black, read (%d, %d, %d)".formatted(night[0], night[1], night[2]));
			}

			// The gameplay clock is its own: noon there, and the sky stays as dark.
			server.runCommand("time set noon");
			int[] noonAtNight = skyAbove(context, "sky-night-at-gameplay-noon");
			if (noonAtNight[0] > 0x18 || noonAtNight[1] > 0x18 || noonAtNight[2] > 0x18) {
				throw new AssertionError("Gameplay noon should not light the dark sky, read (%d, %d, %d)".formatted(noonAtNight[0], noonAtNight[1], noonAtNight[2]));
			}
		}
	}

	/** The bright pixels of the sun view form one disc that is round, small and off the middle, and the glow dies out soon after it. */
	private static void theSunIsSmallAndRound(ClientGameTestContext context, TestServerContext server) {
		server.runCommand(LOOK_AT_SUN);
		context.waitTicks(40);
		BufferedImage image = read(context.takeScreenshot("sky-sun"));
		int centreX = image.getWidth() / 2;
		int centreY = image.getHeight() / 2;
		int minX = Integer.MAX_VALUE;
		int maxX = -1;
		int minY = Integer.MAX_VALUE;
		int maxY = -1;
		int bright = 0;
		// Rows 100 to 380 skip the readout and the hotbar; the box round the middle skips the crosshair.
		for (int y = 100; y < 380; y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				boolean crosshair = Math.abs(x - centreX) < 14 && Math.abs(y - centreY) < 14;
				if (!crosshair && isBright(image.getRGB(x, y))) {
					bright++;
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
				}
			}
		}
		if (bright == 0) {
			throw new AssertionError("No sun in the view at the dusk sun's place: no pixel is brighter than 0x%X on all three channels".formatted(BRIGHT));
		}
		int width = maxX - minX + 1;
		int height = maxY - minY + 1;
		double fill = bright / (double) (width * height);
		if (width > SUN_MAX_WIDTH || height > SUN_MAX_WIDTH) {
			throw new AssertionError("The sun should be small, under %d pixels across; its bright part is %d x %d".formatted(SUN_MAX_WIDTH, width, height));
		}
		if (fill < ROUND_MIN || fill > ROUND_MAX) {
			throw new AssertionError("The sun should be round: a disc fills 0.79 to 0.9 of its bounding box, a square 1.0; its bright part is %d x %d with %d pixels, a fill of %.2f".formatted(width, height, bright, fill));
		}
		// The glow: the row through the middle of the sun has left the sky's own colour behind within GLOW_MAX_RADIUS of the middle.
		int sunX = (minX + maxX) / 2;
		int sunY = (minY + maxY) / 2;
		int sky = luma(image.getRGB(sunX + GLOW_MAX_RADIUS + 40, sunY));
		int differing = 0;
		for (int dx = GLOW_MAX_RADIUS; dx <= GLOW_MAX_RADIUS + 40; dx++) {
			for (int side : new int[] {-1, 1}) {
				if (Math.abs(luma(image.getRGB(sunX + side * dx, sunY)) - sky) > 6) {
					differing++;
				}
			}
		}
		// A few pixels may be a speck of dust.
		if (differing > DUST_PIXELS) {
			throw new AssertionError("The glow should be narrow: %d pixels of the row from %d to %d pixels off the middle of the sun differ from the sky".formatted(differing, GLOW_MAX_RADIUS, GLOW_MAX_RADIUS + 40));
		}
	}

	private static boolean isBright(int rgb) {
		return ((rgb >> 16) & 0xFF) >= BRIGHT && ((rgb >> 8) & 0xFF) >= BRIGHT && (rgb & 0xFF) >= BRIGHT;
	}

	private static int luma(int rgb) {
		return (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
	}

	/** The average colour of a patch at the middle of the screen, looking straight up, once the client has caught up. */
	private static int[] skyAbove(ClientGameTestContext context, String shotName) {
		context.waitTicks(40);
		BufferedImage image = read(context.takeScreenshot(shotName));
		int half = 20;
		long[] sum = new long[3];
		int count = 0;
		for (int y = image.getHeight() / 4 - half; y < image.getHeight() / 4 + half; y++) {
			for (int x = image.getWidth() / 2 - half; x < image.getWidth() / 2 + half; x++) {
				int rgb = image.getRGB(x, y);
				sum[0] += (rgb >> 16) & 0xFF;
				sum[1] += (rgb >> 8) & 0xFF;
				sum[2] += rgb & 0xFF;
				count++;
			}
		}
		return new int[] {(int) (sum[0] / count), (int) (sum[1] / count), (int) (sum[2] / count)};
	}

	private static BufferedImage read(Path shot) {
		try {
			return ImageIO.read(shot.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
