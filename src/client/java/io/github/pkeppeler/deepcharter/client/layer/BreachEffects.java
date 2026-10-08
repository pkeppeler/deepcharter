package io.github.pkeppeler.deepcharter.client.layer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * What the client does after a crossing: a rumble (a sound and a short HUD jitter) and a fade to black
 * and back. All of it is counted in client ticks from the moment the payload arrives; nothing here
 * touches the camera. The transmission that comes with a crossing is the transmission feature's
 * ({@code client/transmission}), drawn over this fade.
 */
public final class BreachEffects {
	/** The whole fade: 20 ticks is one second. */
	public static final int FADE_TICKS = 20;
	/** Ticks spent fading to black, then held fully black, before fading back for the rest of {@link #FADE_TICKS}. */
	private static final int FADE_IN_TICKS = 8;
	private static final int BLACK_TICKS = 4;
	private static final int JITTER_TICKS = 10;
	private static final int JITTER_PIXELS = 3;

	/** A HUD offset in pixels. */
	public record Offset(int x, int y) {
	}

	private static final Offset NONE = new Offset(0, 0);

	/** Ticks since the last crossing; no effects running is {@code -1}. */
	private static int elapsed = -1;

	private BreachEffects() {
	}

	/** Starts every effect. Called on the client thread when the payload arrives. */
	public static void begin() {
		elapsed = 0;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.GENERIC_EXPLODE.value(), 0.4f, 0.8f));
	}

	public static void reset() {
		elapsed = -1;
	}

	public static void tick() {
		if (elapsed < 0) {
			return;
		}
		elapsed++;
		if (elapsed > FADE_TICKS) {
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
}
