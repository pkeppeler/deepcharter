package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How a transmission looks ({@code theme/transmission.json}): the panel over the HUD and the colour of its text. Colours are ARGB,
 * lengths are GUI pixels, and the hold is a visual timing, in client ticks.
 *
 * @param panelFillColor the panel's fill: the screen background, nearly opaque, so the text reads over any view
 * @param textColor the text, and the cursor block after it
 * @param liveColor the header of a live transmission
 * @param relayColor the header of a relayed one
 * @param unknownColor the header of one from an unknown sender
 * @param maxWidth the widest the panel gets
 * @param screenMargin space kept free at each side of the screen
 * @param centerY the panel's vertical centre, as a fraction of the screen height
 * @param headerGap space between the header and the text
 * @param holdTicks ticks the finished transmission stays up before the next one starts
 */
public record TransmissionLook(
		int panelFillColor,
		int textColor,
		int liveColor,
		int relayColor,
		int unknownColor,
		int maxWidth,
		int screenMargin,
		double centerY,
		int headerGap,
		int holdTicks) {
	public static TransmissionLook current() {
		return UiTheme.current().transmission();
	}

	/** The longest a finished transmission may stay up: one minute. */
	static final int MAX_HOLD_TICKS = 1200;

	public static TransmissionLook of(ThemeData d) {
		return new TransmissionLook(d.color("panelFillColor"), d.color("textColor"), d.color("liveColor"), d.color("relayColor"),
				d.color("unknownColor"), d.integer("maxWidth", 1), d.integer("screenMargin", 0), d.decimal("centerY", 0, 1),
				d.integer("headerGap", 0), d.integer("holdTicks", 1, MAX_HOLD_TICKS));
	}
}
