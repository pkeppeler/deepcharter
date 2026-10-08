package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How a layer crossing looks ({@code theme/breach.json}): the fade and the HUD shake. All timings are visual and count client ticks
 * from the moment the crossing's payload arrives; 20 ticks are one second.
 *
 * @param fadeTicks the whole fade, in, held, out; at most 100
 * @param fadeInTicks ticks spent fading in
 * @param blackTicks ticks held fully faded after that, before fading back for the rest of {@code fadeTicks}
 * @param jitterTicks ticks the HUD shakes, settling as it goes
 * @param jitterPixels how far the HUD shakes at first, in GUI pixels
 * @param fadeColor the colour faded to; its alpha is ignored, the fade sets it
 */
public record BreachLook(int fadeTicks, int fadeInTicks, int blackTicks, int jitterTicks, int jitterPixels, int fadeColor) {
	public static BreachLook current() {
		return UiTheme.current().breach();
	}

	/** The longest a fade or shake may last, so that a pack cannot black out the HUD for long. */
	static final int MAX_TICKS = 100;

	public static BreachLook of(ThemeData d) {
		BreachLook look = new BreachLook(d.integer("fadeTicks", 1, MAX_TICKS), d.integer("fadeInTicks", 1, MAX_TICKS),
				d.integer("blackTicks", 0, MAX_TICKS), d.integer("jitterTicks", 1, MAX_TICKS), d.integer("jitterPixels", 0, 32),
				d.color("fadeColor"));
		if (look.fadeInTicks() + look.blackTicks() >= look.fadeTicks()) {
			throw d.conflict("fadeInTicks + blackTicks must be less than fadeTicks, or the fade has no way back",
					"fadeTicks", "fadeInTicks", "blackTicks");
		}
		return look;
	}
}
