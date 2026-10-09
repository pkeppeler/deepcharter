package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How the text readouts of the HUD look ({@code theme/hud.json}): the pod status at top left, the altimeter at top centre and the
 * account line at the left. Colours are ARGB, lengths are GUI pixels.
 *
 * @param podStatusMargin space between the pod readout and the screen's top and left edges
 * @param podStatusLineGap space between its lines, on top of the font height
 * @param podStatusColor its text
 * @param podBurningColor the warning line that shows while lava burns the hull
 * @param podLiningColor the line that shows while the pilot is lining the slab with slag brick
 * @param podLiningDryColor the warning line that shows when lining stopped for want of slag brick
 * @param altimeterMargin space between the altimeter and the top edge
 * @param altimeterColor its text
 * @param accountMargin space between the account line and the left edge
 * @param accountY where the account line sits, as a fraction of the screen height
 * @param accountColor its text
 */
public record HudLook(
		int podStatusMargin,
		int podStatusLineGap,
		int podStatusColor,
		int podBurningColor,
		int podLiningColor,
		int podLiningDryColor,
		int altimeterMargin,
		int altimeterColor,
		int accountMargin,
		double accountY,
		int accountColor) {
	public static HudLook current() {
		return UiTheme.current().hud();
	}

	public static HudLook of(ThemeData d) {
		return new HudLook(d.integer("podStatusMargin", 0), d.integer("podStatusLineGap", 0), d.color("podStatusColor"),
				d.color("podBurningColor"), d.color("podLiningColor"), d.color("podLiningDryColor"),
				d.integer("altimeterMargin", 0), d.color("altimeterColor"), d.integer("accountMargin", 0), d.decimal("accountY", 0, 1),
				d.color("accountColor"));
	}
}
