package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How the scanner minimap looks ({@code theme/scanner.json}). Colours are ARGB; the HUD draws them with fill(), which ignores world light.
 *
 * @param cellPixels GUI pixels per cell
 * @param margin GUI pixels between the panel and the screen edge
 * @param titleColor the panel's title
 * @param airColor air, and the panel's background
 * @param rockColor solid rock
 * @param oreColor ore
 * @param goldOreColor gold ore
 * @param lavaColor a cell with lava in the plane, on a scanner that marks lava
 * @param lavaNearColor a cell with lava only beside the plane: dim, so that it recedes behind the lava in the plane
 * @param gasColor a gas pocket
 * @param podColor the ridden pod
 * @param frameColor the panel's frame
 */
public record ScannerLook(
		int cellPixels,
		int margin,
		int titleColor,
		int airColor,
		int rockColor,
		int oreColor,
		int goldOreColor,
		int lavaColor,
		int lavaNearColor,
		int gasColor,
		int podColor,
		int frameColor) {
	public static ScannerLook current() {
		return UiTheme.current().scanner();
	}

	public static ScannerLook of(ThemeData d) {
		return new ScannerLook(d.integer("cellPixels", 1), d.integer("margin", 0), d.color("titleColor"), d.color("airColor"),
				d.color("rockColor"), d.color("oreColor"), d.color("goldOreColor"), d.color("lavaColor"), d.color("lavaNearColor"), d.color("gasColor"), d.color("podColor"),
				d.color("frameColor"));
	}
}
