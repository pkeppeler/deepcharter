package io.github.pkeppeler.deepcharter.test;

import java.util.Locale;
import java.util.Set;
import java.util.function.ToIntFunction;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.client.layer.Altimeter;
import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.layer.LayerChain;

/** Client GameTest: the altimeter string matches the depth maths, and a crossing starts and clears the fade. */
public class BreachHudTest implements FabricClientGameTest {
	private static final double X = 2000.5;
	private static final double Z = 2000.5;
	/** Real ticks to wait for something that should take a handful. */
	private static final int PATIENCE = 200;

	/** Written out here, not read from Depth, so the test checks the maths rather than repeating it. */
	private static final int SEA_LEVEL = 63;
	private static final int OVERWORLD_MIN_Y = -64;
	private static final int LAYER_1_HEIGHT = 192;
	private static final double FEET_PER_BLOCK = 3.28;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);

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
				int shown = context.computeOnClient(client -> BreachEffects.transmissionShown().length());
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
			if (BreachEffects.transmissionFull().isBlank()) {
				throw new AssertionError("The stub transmission has no text");
			}
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
