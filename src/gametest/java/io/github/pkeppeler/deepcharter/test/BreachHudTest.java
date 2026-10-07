package io.github.pkeppeler.deepcharter.test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToIntFunction;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.client.layer.Altimeter;
import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.layer.BreachPayload;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;

/** Client GameTest: the altimeter string matches the depth maths, and a crossing starts and clears the fade. */
public class BreachHudTest implements FabricClientGameTest {
	private static final double X = 2000.5;
	private static final double Z = 2000.5;
	/** Real ticks to wait for something that should take a handful. */
	private static final int PATIENCE = 200;
	/** Pixels of the screen's top-left corner that the pod readout's four lines cover, at any GUI scale up to 4. */
	private static final int POD_READOUT_WIDTH = 60;
	private static final int POD_READOUT_HEIGHT = 40;

	/** Written out here, not read from Depth, so the test checks the maths rather than repeating it. */
	private static final int SEA_LEVEL = 63;
	private static final int OVERWORLD_MIN_Y = -64;
	private static final int LAYER_1_HEIGHT = 192;
	private static final double FEET_PER_BLOCK = 3.28;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);

			assertFadeCoversPodHud(context, singleplayer);

			// On the surface the altimeter reads sea level minus Y.
			assertAltimeter(context, "the surface", client -> SEA_LEVEL - client.player.getBlockY());

			// In layer 1 low enough that the reading is over a thousand feet, so it has a thousands separator.
			teleport(singleplayer, 1, 5);
			context.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(1)));
			assertAltimeter(context, "layer 1", client ->
					SEA_LEVEL - OVERWORLD_MIN_Y + LAYER_1_HEIGHT - client.player.getBlockY());
			String deep = context.computeOnClient(client -> Altimeter.reading(client).getString());
			if (!deep.contains(",")) {
				throw new AssertionError("A reading over 1,000 ft should group its digits, read: " + deep);
			}

			// A crossing: one block under the floor of layer 1, so it crosses on the next server tick.
			teleport(singleplayer, 1, -1);
			float peak = 0;
			int startedAt = -1;
			int clearedAt = -1;
			int lastShown = 0;
			for (int tick = 0; tick < PATIENCE && clearedAt < 0; tick++) {
				float alpha = context.computeOnClient(client -> BreachEffects.fadeAlpha(0f));
				int shown = context.computeOnClient(client -> BreachEffects.transmissionShown().stream().mapToInt(String::length).sum());
				if (shown < lastShown) {
					throw new AssertionError("The transmission untyped itself: " + lastShown + " then " + shown + " characters");
				}
				lastShown = shown;
				if (alpha > 0 && startedAt < 0) {
					startedAt = tick;
				}
				peak = Math.max(peak, alpha);
				if (startedAt >= 0 && alpha == 0) {
					clearedAt = tick;
				}
				context.waitTick();
			}
			if (startedAt < 0) {
				throw new AssertionError("The fade never started after a crossing");
			}
			if (peak < 0.8f) {
				throw new AssertionError("The fade should reach black, peaked at " + peak);
			}
			if (clearedAt < 0) {
				throw new AssertionError("The fade did not clear within " + PATIENCE + " ticks");
			}
			int length = clearedAt - startedAt;
			// Sampling is per tick, so allow a few ticks either side of the nominal length.
			if (length < BreachEffects.FADE_TICKS - 3 || length > BreachEffects.FADE_TICKS + 5) {
				throw new AssertionError("The fade should last about " + BreachEffects.FADE_TICKS + " ticks, lasted " + length);
			}
			if (!context.computeOnClient(client -> client.level.dimension().equals(LayerChain.dimension(2)))) {
				throw new AssertionError("The crossing should have put the client in layer 2");
			}

			// The transmission types out and finishes with the whole text.
			context.waitFor(client -> !BreachEffects.transmissionShown().isEmpty());
			context.waitFor(client -> BreachEffects.transmissionShown().equals(BreachEffects.transmissionFull()));
			List<String> full = context.computeOnClient(client -> BreachEffects.transmissionFull());
			if (full.isEmpty() || full.stream().anyMatch(String::isBlank)) {
				throw new AssertionError("The stub transmission should have text on every line, has " + full);
			}
			// A missing lang key would render as the raw key, so check each one exists.
			for (boolean descent : new boolean[] {true, false}) {
				for (String key : BreachEffects.transmissionKeys(descent)) {
					if (!context.computeOnClient(client -> Language.getInstance().has(key))) {
						throw new AssertionError("Transmission lang key missing from en_us.json: " + key);
					}
				}
			}
		}
	}

	/**
	 * The fade is a full blackout, so it must draw over the pod readout (registered by another part of
	 * the mod). The registry does not expose its order, so this looks at pixels: the readout's corner
	 * has bright text before the fade and none at its black plateau.
	 */
	private static void assertFadeCoversPodHud(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		PodShellClientTest.mountFirstPlayer(singleplayer.getServer());
		context.waitFor(client -> client.player.getVehicle() instanceof PodEntity);
		context.waitTicks(5);
		if (brightestInPodReadout(context.takeScreenshot("breach-hud-before-fade")) < 200) {
			throw new AssertionError("The pod readout should be visible before the fade, or this check proves nothing");
		}
		context.runOnClient(client -> BreachEffects.begin(new BreachPayload(1, 2)));
		context.waitFor(client -> BreachEffects.fadeAlpha(0f) >= 1f);
		int brightest = brightestInPodReadout(context.takeScreenshot("breach-hud-fade-peak"));
		if (brightest > 8) {
			throw new AssertionError("The fade should black out the pod readout, but a pixel of brightness " + brightest + " shows through");
		}
		context.runOnClient(client -> BreachEffects.reset());
		singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().stopRiding());
		context.waitFor(client -> client.player.getVehicle() == null);
	}

	/** The brightest colour channel in the top-left corner of the screenshot, where the pod readout draws. */
	private static int brightestInPodReadout(Path screenshot) {
		try {
			BufferedImage image = ImageIO.read(screenshot.toFile());
			int brightest = 0;
			for (int x = 0; x < POD_READOUT_WIDTH; x++) {
				for (int y = 0; y < POD_READOUT_HEIGHT; y++) {
					int argb = image.getRGB(x, y);
					brightest = Math.max(brightest, Math.max((argb >> 16) & 0xFF, Math.max((argb >> 8) & 0xFF, argb & 0xFF)));
				}
			}
			return brightest;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Teleports the real player into layer {@code layer} at height {@code y}. */
	private static void teleport(TestSingleplayerContext singleplayer, int layer, int y) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(layer));
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.teleportTo(level, X, y, Z, Set.of(), 0, 0, true);
		});
	}

	/**
	 * The reading and the block Y it was made from are taken in one client call, so the player cannot
	 * move between them.
	 */
	private static void assertAltimeter(ClientGameTestContext context, String where,
			ToIntFunction<Minecraft> blocksDeep) {
		record Sample(String text, int blocks) {
		}
		Sample sample = context.computeOnClient(client -> new Sample(Altimeter.reading(client).getString(), blocksDeep.applyAsInt(client)));
		int feet = (int) Math.round(sample.blocks() * FEET_PER_BLOCK);
		String expected = String.format(Locale.US, "%,d", -feet) + " ft.";
		if (!sample.text().equals(expected)) {
			throw new AssertionError("Altimeter in " + where + " should read '" + expected + "' for " + sample.blocks()
					+ " blocks of depth, read '" + sample.text() + "'");
		}
	}
}
