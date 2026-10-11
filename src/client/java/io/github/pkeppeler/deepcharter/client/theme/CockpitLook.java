package io.github.pkeppeler.deepcharter.client.theme;

import java.util.Optional;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook.Decal;
import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * The cockpit HUD a pilot sees from the seat ({@code theme/cockpit.json}, #247, docs/design/cockpit-concepts.md). {@code style} picks one of the
 * options below, or 0 for none: then the HUD is the plain text readout, the scanner at top right and the vanilla HUD, as it was. A style
 * is a frame over the whole screen, a few decals of housings, a gauge for each of fuel, hull, depth and heat, a place for the scanner and a place
 * for the text readout. Its sprites are GUI sprites under {@code textures/gui/sprites/cockpit/<style>/} ({@link Layout#sprite}); the gauges only
 * draw what moves, on the dial or bar the sprites left room for. A pack restyles the colours or picks a style by key. Colours are ARGB, lengths are GUI pixels.
 *
 * @param style which option is drawn
 * @param needleColor a dial's needle and hub
 * @param fuelColor the fuel gauge
 * @param hullColor the hull gauge
 * @param depthColor the depth gauge
 * @param heatColor the heat gauge
 * @param lowColor a gauge that is in warning: fuel at the low-fuel beep, hull under {@code hullLowFraction}
 * @param offColor an unlit bar segment
 * @param hullLowFraction the share of the hull from which and below the hull gauge warns
 * @param heatRisePerTick how fast the heat gauge climbs toward 1 while lava burns the hull, per client tick
 * @param heatFallPerTick how fast it falls back to 0 when the hull stops burning, per client tick
 * @param platePad the space between the readout plate's edge and its text
 * @param needleInset how far a needle stops short of the edge of its dial
 * @param hubSize the side of the square hub a needle turns on
 * @param heatWarning the heat from which the heat gauge is in warning
 * @param scannerTitleGap the space between the scanner window and its title under it
 */
public record CockpitLook(
		Style style,
		Layout dials,
		Layout strip,
		Layout consoles,
		int needleColor,
		int fuelColor,
		int hullColor,
		int depthColor,
		int heatColor,
		int lowColor,
		int offColor,
		double hullLowFraction,
		double heatRisePerTick,
		double heatFallPerTick,
		int platePad,
		int needleInset,
		int hubSize,
		double heatWarning,
		int scannerTitleGap) {
	/** The options. The ordinal is the {@code style} value in the theme. */
	public enum Style {
		OFF("off"),
		DIALS("dials"),
		STRIP("strip"),
		CONSOLES("consoles");

		private final String slug;

		Style(String slug) {
			this.slug = slug;
		}

		public String slug() {
			return slug;
		}
	}

	/** What a gauge looks like: a needle on a dial, or a row of segments filling across or up. */
	public enum Kind {
		DIAL,
		ACROSS,
		UP
	}

	/**
	 * One gauge: where it sits and how it reads. A width of 0 means the style has no such gauge.
	 *
	 * @param kind a dial, or a bar that fills across or up
	 * @param area the dial's face or the bar's slot; a dial's needle turns about the centre
	 * @param from for a dial, the needle's angle at 0, in degrees clockwise from straight up
	 * @param to for a dial, the angle at 1
	 * @param segments for a bar, how many segments it is cut in
	 */
	public record Gauge(Kind kind, Decal area, int from, int to, int segments) {
		static Gauge of(ThemeData d, String prefix) {
			return new Gauge(Kind.values()[d.integer(prefix + "Kind", 0, Kind.values().length - 1)], Decal.of(d, prefix),
					d.integer(prefix + "From", -360, 360), d.integer(prefix + "To", -360, 360), d.integer(prefix + "Segments", 1, 64));
		}

		public boolean on() {
			return area.on();
		}
	}

	/** A point put at a corner, an edge or the centre of the screen and moved by an offset; the box that hangs from it is as big as its content. */
	public record Anchor(int anchorX, int anchorY, int x, int y) {
		static Anchor of(ThemeData d, String prefix) {
			return new Anchor(d.integer(prefix + "AnchorX", 0, 2), d.integer(prefix + "AnchorY", 0, 2), d.integer(prefix + "X", -1024, 1024),
					d.integer(prefix + "Y", -1024, 1024));
		}

		/** The left edge of a box {@code width} wide on a screen {@code screenWidth} wide. */
		public int left(int screenWidth, int width) {
			return new Decal(width, 1, anchorX, anchorY, x, y).left(screenWidth);
		}

		/** The top edge of a box {@code height} high on a screen {@code screenHeight} high. */
		public int top(int screenHeight, int height) {
			return new Decal(1, height, anchorX, anchorY, x, y).top(screenHeight);
		}
	}

	/**
	 * One style's layout. The frame and the plate are nine-slice sprites; the others are fixed-size sprites placed by their decal. A decal with
	 * a width of 0 is off.
	 *
	 * @param name the style's slug, which names its sprite folder
	 * @param header a decal drawn after the gauges, under the altimeter
	 * @param partA a housing, drawn after the frame
	 * @param partB another housing
	 * @param cap drawn over the scanner's cells: the bezel that masks them
	 * @param fuel the fuel gauge
	 * @param hull the hull gauge
	 * @param depth the depth gauge
	 * @param heat the heat gauge
	 * @param scanner where the scanner's cells go (and its title, under them)
	 * @param readout where the text readout's plate hangs
	 * @param altimeterY the altimeter's top, as the HUD theme's {@code altimeterMargin} is without a cockpit
	 */
	public record Layout(
			String name,
			Decal header,
			Decal partA,
			Decal partB,
			Decal cap,
			Gauge fuel,
			Gauge hull,
			Gauge depth,
			Gauge heat,
			Decal scanner,
			Anchor readout,
			int altimeterY) {
		static Layout of(ThemeData d, Style style) {
			String p = style.slug();
			return new Layout(p, Decal.of(d, p + "Header"), Decal.of(d, p + "PartA"), Decal.of(d, p + "PartB"), Decal.of(d, p + "Cap"),
					Gauge.of(d, p + "Fuel"), Gauge.of(d, p + "Hull"), Gauge.of(d, p + "Depth"), Gauge.of(d, p + "Heat"), Decal.of(d, p + "Scanner"),
					Anchor.of(d, p + "Readout"), d.integer(p + "AltimeterY", 0, 200));
		}

		/** The GUI sprite {@code slot} of this style: {@code frame}, {@code plate}, {@code header}, {@code part_a}, {@code part_b} or {@code cap}. */
		public Identifier sprite(String slot) {
			return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "cockpit/" + name + "/" + slot);
		}
	}

	public static CockpitLook current() {
		return UiTheme.current().cockpit();
	}

	/** The layout of the style in force, or empty when the style is 0. */
	public Optional<Layout> active() {
		return switch (style) {
			case OFF -> Optional.empty();
			case DIALS -> Optional.of(dials);
			case STRIP -> Optional.of(strip);
			case CONSOLES -> Optional.of(consoles);
		};
	}

	public static CockpitLook of(ThemeData d) {
		return new CockpitLook(Style.values()[d.integer("style", 0, Style.values().length - 1)], Layout.of(d, Style.DIALS), Layout.of(d, Style.STRIP),
				Layout.of(d, Style.CONSOLES), d.color("needleColor"), d.color("fuelColor"), d.color("hullColor"), d.color("depthColor"), d.color("heatColor"),
				d.color("lowColor"), d.color("offColor"), d.decimal("hullLowFraction", 0, 1), d.decimal("heatRisePerTick", 0.001, 1),
				d.decimal("heatFallPerTick", 0.001, 1), d.integer("platePad", 0, 16),
				d.integer("needleInset", 0, 16), d.integer("hubSize", 1, 9), d.decimal("heatWarning", 0, 1), d.integer("scannerTitleGap", 0, 16));
	}
}
