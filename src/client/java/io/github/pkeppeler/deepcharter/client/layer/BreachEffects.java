package io.github.pkeppeler.deepcharter.client.layer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.locale.Language;

import io.github.pkeppeler.deepcharter.layer.BreachPayload;
import io.github.pkeppeler.deepcharter.layer.LayerChain;

/**
 * What the client does after a crossing: a rumble (a sound and a short HUD jitter), a fade to black
 * and back, and a transmission typed out like a typewriter. All of it is counted in client ticks
 * from the moment the payload arrives; nothing here touches the camera.
 */
public final class BreachEffects {
	/** The whole fade: 20 ticks is one second. */
	public static final int FADE_TICKS = 20;
	/** Ticks spent fading to black, then held fully black, before fading back for the rest of {@link #FADE_TICKS}. */
	private static final int FADE_IN_TICKS = 8;
	private static final int BLACK_TICKS = 4;
	private static final int JITTER_TICKS = 10;
	private static final int JITTER_PIXELS = 3;
	private static final int CHARACTERS_PER_TICK = 2;
	/** Ticks the finished transmission stays up. */
	private static final int HOLD_TICKS = 80;
	/** The one line shown on arriving at the surface, which has no layer number to name. */
	private static final String SURFACE_KEY = "deepcharter.surface.transmission.arrival";

	/** A HUD offset in pixels. */
	public record Offset(int x, int y) {
	}

	private static final Offset NONE = new Offset(0, 0);

	/** Ticks since the last crossing; no effects running is {@code -1}. */
	private static int elapsed = -1;
	private static List<String> transmission = List.of();

	private BreachEffects() {
	}

	/** Starts every effect. Called on the client thread when the payload arrives. */
	public static void begin(BreachPayload payload) {
		elapsed = 0;
		List<String> keys = payload.toLayer() == LayerChain.SURFACE ? List.of(SURFACE_KEY) : transmissionKeys(payload.descent());
		transmission = keys.stream()
				.map(key -> text(key, payload.toLayer()))
				.toList();
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.GENERIC_EXPLODE.value(), 0.4f, 0.8f));
	}

	public static void reset() {
		elapsed = -1;
		transmission = List.of();
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

	/** 0 is clear, 1 is black: up, a short stretch of full black, then down, all within {@link #FADE_TICKS}. */
	public static float fadeAlpha(float partialTick) {
		if (elapsed < 0) {
			return 0f;
		}
		float t = elapsed + partialTick;
		if (t >= FADE_TICKS) {
			return 0f;
		}
		if (t < FADE_IN_TICKS) {
			return t / FADE_IN_TICKS;
		}
		int fadeOutStart = FADE_IN_TICKS + BLACK_TICKS;
		if (t < fadeOutStart) {
			return 1f;
		}
		return 1f - (t - fadeOutStart) / (FADE_TICKS - fadeOutStart);
	}

	/** The HUD offset, shaking hard at first and settling over {@link #JITTER_TICKS}. */
	public static Offset jitter() {
		if (elapsed < 0 || elapsed >= JITTER_TICKS) {
			return NONE;
		}
		double amplitude = JITTER_PIXELS * (1.0 - (double) elapsed / JITTER_TICKS);
		return new Offset((int) Math.round(amplitude * Math.sin(elapsed * 2.1)), (int) Math.round(amplitude * Math.cos(elapsed * 3.3)));
	}

	/** The typed part of the transmission, line by line; empty when none is showing. */
	public static List<String> transmissionShown() {
		if (elapsed < 0) {
			return List.of();
		}
		int remaining = elapsed * CHARACTERS_PER_TICK;
		List<String> shown = new ArrayList<>();
		for (String line : transmission) {
			if (remaining <= 0) {
				break;
			}
			shown.add(line.substring(0, Math.min(line.length(), remaining)));
			remaining -= line.length();
		}
		return shown;
	}

	/** The whole transmission, whether or not it has finished typing; empty when none is showing. */
	public static List<String> transmissionFull() {
		return transmission;
	}

	/** The lang keys of a transmission's lines. Placeholder text until the lore session writes the real ones. */
	public static List<String> transmissionKeys(boolean descent) {
		String prefix = descent ? "deepcharter.breach.transmission.descent." : "deepcharter.breach.transmission.ascent.";
		int lines = descent ? 2 : 1;
		return IntStream.rangeClosed(1, lines).mapToObj(line -> prefix + line).toList();
	}

	private static String text(String key, int layer) {
		if (!Language.getInstance().has(key)) {
			throw new IllegalStateException("Missing lang key " + key);
		}
		return Component.translatable(key, layer).getString();
	}

	private static int transmissionTicks() {
		int characters = transmission.stream().mapToInt(String::length).sum();
		return (characters + CHARACTERS_PER_TICK - 1) / CHARACTERS_PER_TICK;
	}
}
