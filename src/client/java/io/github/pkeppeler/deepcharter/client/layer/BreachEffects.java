package io.github.pkeppeler.deepcharter.client.layer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import io.github.pkeppeler.deepcharter.client.theme.BreachLook;

/**
 * What the client does after a crossing: a rumble (a sound and a short HUD jitter) and a fade to black
 * and back. All of it is counted in client ticks from the moment the payload arrives; nothing here
 * touches the camera. The transmission that comes with a crossing is the transmission feature's
 * ({@code client/transmission}), drawn over this fade. How long each effect lasts is the theme's ({@link BreachLook}).
 */
public final class BreachEffects {
	/** A HUD offset in pixels. */
	public record Offset(int x, int y) {
	}

	private static final Offset NONE = new Offset(0, 0);

	/** Ticks since the last crossing; no effects running is {@code -1}. */
	private static int elapsed = -1;

	private BreachEffects() {
	}

	/** The whole fade, in client ticks: 20 ticks is one second. */
	public static int fadeTicks() {
		return BreachLook.current().fadeTicks();
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
		if (elapsed > fadeTicks()) {
			reset();
		}
	}

	/** 0 is clear, 1 is black: up, a short stretch of full black, then down, all within {@link #fadeTicks()}. */
	public static float fadeAlpha(float partialTick) {
		if (elapsed < 0) {
			return 0f;
		}
		BreachLook look = BreachLook.current();
		float t = elapsed + partialTick;
		if (t >= look.fadeTicks()) {
			return 0f;
		}
		if (t < look.fadeInTicks()) {
			return t / look.fadeInTicks();
		}
		int fadeOutStart = look.fadeInTicks() + look.blackTicks();
		if (t < fadeOutStart) {
			return 1f;
		}
		return 1f - (t - fadeOutStart) / (look.fadeTicks() - fadeOutStart);
	}

	/** The HUD offset, shaking hard at first and settling over the look's jitter ticks. */
	public static Offset jitter() {
		BreachLook look = BreachLook.current();
		if (elapsed < 0 || elapsed >= look.jitterTicks()) {
			return NONE;
		}
		double amplitude = look.jitterPixels() * (1.0 - (double) elapsed / look.jitterTicks());
		return new Offset((int) Math.round(amplitude * Math.sin(elapsed * 2.1)), (int) Math.round(amplitude * Math.cos(elapsed * 3.3)));
	}
}
