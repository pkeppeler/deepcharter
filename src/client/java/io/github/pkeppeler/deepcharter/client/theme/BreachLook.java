package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How a layer crossing looks ({@code theme/breach.json}): the fade and the HUD shake. All timings are visual and count client ticks
 * from the moment the crossing's payload arrives; 20 ticks are one second.
 *
 * @param fadeTicks the whole fade, in, held, out
 * @param fadeInTicks ticks spent fading in
 * @param blackTicks ticks held fully faded after that, before fading back for the rest of {@code fadeTicks}
 * @param jitterTicks ticks the HUD shakes, settling as it goes
 * @param jitterPixels how far the HUD shakes at first, in GUI pixels
 * @param fadeColor the colour faded to; its alpha is ignored, the fade sets it
 */
public record BreachLook(int fadeTicks, int fadeInTicks, int blackTicks, int jitterTicks, int jitterPixels, int fadeColor) {
	public BreachLook {
		if (fadeInTicks + blackTicks >= fadeTicks) {
			throw new IllegalArgumentException("fadeInTicks + blackTicks (%d + %d) must be less than fadeTicks (%d), or the fade has no way back"
					.formatted(fadeInTicks, blackTicks, fadeTicks));
		}
	}

	public static BreachLook current() {
		return UiTheme.current().breach();
	}

	static BreachLook of(ThemeData d) {
		return new BreachLook(d.integer("fadeTicks", 1), d.integer("fadeInTicks", 1), d.integer("blackTicks", 0), d.integer("jitterTicks", 1),
				d.integer("jitterPixels", 0), d.color("fadeColor"));
	}
}
