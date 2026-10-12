package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How the scanner minimap looks ({@code theme/scanner.json}). Colours are ARGB. The HUD draws a cell as the block's own texture from the block atlas; neither that nor fill() reads
 * world light. The flat colours below are what a cell with no usable sprite falls back to, and the shades, lifts and tints are laid
 * over the texture ({@code #00000000} turns one off).
 *
 * @param cellPixels GUI pixels per cell
 * @param margin GUI pixels between the panel and the screen edge
 * @param titleColor the panel's title
 * @param airColor air, and the panel's background
 * @param rockColor solid rock, when its block has no usable sprite
 * @param oreColor ore, when its block has no usable sprite
 * @param goldOreColor gold ore, when its block has no usable sprite
 * @param lavaColor a cell with lava in the plane, on a scanner that marks lava, when lava has no usable sprite
 * @param lavaNearColor a cell with lava only beside the plane, when lava has no usable sprite
 * @param gasColor a gas pocket, when its block has no usable sprite
 * @param rockShadeColor laid over the texture of every rock cell, so that the flecks of an ore stand out from the grain around it
 * @param oreLiftColor laid over the texture of every ore cell: the brightness lift that keeps ore as easy to spot as its flat colour was
 * @param goldOreLiftColor as {@code oreLiftColor}, for gold ore
 * @param lavaNearShadeColor laid over the texture of a cell with lava only beside the plane: dim, so that it recedes behind the lava in the plane
 * @param gasTintColor laid over the texture of a gas pocket, which looks like stone and so needs a colour to read as gas
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
		int rockShadeColor,
		int oreLiftColor,
		int goldOreLiftColor,
		int lavaNearShadeColor,
		int gasTintColor,
		int podColor,
		int frameColor) {
	public static ScannerLook current() {
		return UiTheme.current().scanner();
	}

	public static ScannerLook of(ThemeData d) {
		return new ScannerLook(d.integer("cellPixels", 1), d.integer("margin", 0), d.color("titleColor"), d.color("airColor"),
				d.color("rockColor"), d.color("oreColor"), d.color("goldOreColor"), d.color("lavaColor"), d.color("lavaNearColor"), d.color("gasColor"),
				d.color("rockShadeColor"), d.color("oreLiftColor"), d.color("goldOreLiftColor"), d.color("lavaNearShadeColor"), d.color("gasTintColor"), d.color("podColor"),
				d.color("frameColor"));
	}
}
