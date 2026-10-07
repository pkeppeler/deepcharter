package io.github.pkeppeler.deepcharter.client.layer;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import io.github.pkeppeler.deepcharter.layer.BreachPayload;

/**
 * What the client does after a crossing: a rumble (a sound and a short HUD jitter), a fade to black
 * and back, and a transmission typed out like a typewriter. All of it is counted in client ticks
 * from the moment the payload arrives; nothing here touches the camera.
 */
public final class BreachEffects {
	/** The whole fade: black at the middle, clear again at the end. 20 ticks is one second. */
	public static final int FADE_TICKS = 20;
	public static final int JITTER_TICKS = 10;
	private static final int JITTER_PIXELS = 3;
	private static final int CHARACTERS_PER_TICK = 2;
	/** Ticks the finished transmission stays up. */
	private static final int HOLD_TICKS = 80;

	/** Ticks since the last crossing; empty effects are {@code -1}. */
	private static int elapsed = -1;
	private static String transmission = "";

	private BreachEffects() {
	}

	/** Starts every effect. Called on the client thread when the payload arrives. */
	public static void begin(BreachPayload payload) {
		elapsed = 0;
		transmission = transmission(payload);
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.GENERIC_EXPLODE.value(), 0.4f, 0.8f));
	}

	public static void reset() {
		elapsed = -1;
		transmission = "";
	}

	public static void tick() {
		if (elapsed < 0) {
			return;
		}
		elapsed++;
		if (elapsed > Math.max(FADE_TICKS, transmissionTicks() + HOLD_TICKS)) {
			reset();
		}
	}

	/** 0 is clear, 1 is black: up for the first half of {@link #FADE_TICKS}, down for the second. */
	public static float fadeAlpha(float partialTick) {
		if (elapsed < 0) {
			return 0f;
		}
		float t = (elapsed + partialTick) / FADE_TICKS;
		if (t >= 1f) {
			return 0f;
		}
		return 1f - Math.abs(2f * t - 1f);
	}

	/** The HUD offset in pixels, shaking hard at first and settling over {@link #JITTER_TICKS}. */
	public static int[] jitter() {
		if (elapsed < 0 || elapsed >= JITTER_TICKS) {
			return new int[] {0, 0};
		}
		double amplitude = JITTER_PIXELS * (1.0 - (double) elapsed / JITTER_TICKS);
		return new int[] {(int) Math.round(amplitude * Math.sin(elapsed * 2.1)), (int) Math.round(amplitude * Math.cos(elapsed * 3.3))};
	}

	/** The typed part of the transmission, lines joined by newlines; empty when none is showing. */
	public static String transmissionShown() {
		if (elapsed < 0) {
			return "";
		}
		return transmission.substring(0, Math.min(transmission.length(), elapsed * CHARACTERS_PER_TICK));
	}

	/** The whole transmission, whether or not it has finished typing; empty when none is showing. */
	public static String transmissionFull() {
		return transmission;
	}

	private static int transmissionTicks() {
		return (transmission.length() + CHARACTERS_PER_TICK - 1) / CHARACTERS_PER_TICK;
	}

	/** Placeholder text until the lore session writes the real transmissions. */
	private static String transmission(BreachPayload payload) {
		String key = payload.toLayer() > payload.fromLayer() ? "deepcharter.breach.transmission.descent." : "deepcharter.breach.transmission.ascent.";
		int lines = payload.toLayer() > payload.fromLayer() ? 2 : 1;
		return IntStream.rangeClosed(1, lines)
				.mapToObj(line -> Component.translatable(key + line, payload.toLayer()).getString())
				.collect(Collectors.joining("\n"));
	}
}
