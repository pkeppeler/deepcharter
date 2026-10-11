package io.github.pkeppeler.deepcharter.client.theme;

import java.util.Optional;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook.Decal;
import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * The cockpit HUD a pilot sees from the seat ({@code theme/cockpit.json}, #247, docs/design/cockpit-concepts.md and cockpit-concepts-2.md). {@code style} picks one of the
 * options below, or 0 for none: then the HUD is the plain text readout, the scanner at top right and the vanilla HUD, as it was. A style is a few
 * decals of housings, a gauge for each of fuel, hull, depth, heat and cargo, a row of warning lamps, a place for the scanner and a place for the readout's plate;
 * round 1's style also has a frame over the whole screen. Its sprites are GUI sprites under {@code textures/gui/sprites/cockpit/<style>/} ({@link Layout#sprite}); the
 * gauges only draw what moves, on the bar the sprites left room for. A pack restyles the colours or picks a style by key. Colours are ARGB, lengths are GUI pixels.
 *
 * @param style which option is drawn
 * @param consoles round 1's look, kept to compare: a frame, two consoles, every line of the readout
 * @param corners a small housing in each corner
 * @param ribbon one slim ribbon along the top edge
 * @param posts a post up each side of the view and a sill
 * @param cab only a small instrument sill, so the pod's own cab is the frame
 * @param fuelColor the fuel gauge
 * @param hullColor the hull gauge
 * @param depthColor the depth gauge
 * @param heatColor the heat gauge
 * @param cargoColor the cargo gauge
 * @param lowColor a gauge that is in warning: fuel at the low-fuel beep, hull under {@code hullLowFraction}
 * @param offColor an unlit bar segment or lamp
 * @param hullLowFraction the share of the hull from which and below the hull gauge warns
 * @param heatRisePerTick how fast the heat gauge climbs toward 1 while lava burns the hull, per client tick
 * @param heatFallPerTick how fast it falls back to 0 when the hull stops burning, per client tick
 * @param platePad the space between the readout plate's edge and its text
 * @param heatWarning the heat from which the heat gauge is in warning
 * @param scannerTitleGap the space between the scanner window and its title under it
 * @param lampGap the space between two warning lamps
 * @param tallyGap the space between two numbers of a compact plate's line
 * @param camera the front camera's feed (#448)
 */
public record CockpitLook(
		Style style,
		Layout consoles,
		Layout corners,
		Layout ribbon,
		Layout posts,
		Layout cab,
		int fuelColor,
		int hullColor,
		int depthColor,
		int heatColor,
		int cargoColor,
		int lowColor,
		int offColor,
		double hullLowFraction,
		double heatRisePerTick,
		double heatFallPerTick,
		int platePad,
		double heatWarning,
		int scannerTitleGap,
		int lampGap,
		int tallyGap,
		CameraLook camera) {
	/** The options. The ordinal is the {@code style} value in the theme. */
	public enum Style {
		OFF("off"),
		CONSOLES("consoles"),
		CORNERS("corners"),
		RIBBON("ribbon"),
		POSTS("posts"),
		CAB("cab");

		private final String slug;

		Style(String slug) {
			this.slug = slug;
		}

		public String slug() {
			return slug;
		}
	}

	/** How a bar fills: segments lit from its start across, or up. */
	public enum Kind {
		ACROSS,
		UP
	}

	/**
	 * One gauge: where it sits and how it reads. A width of 0 means the style has no such gauge.
	 *
	 * @param kind a bar that fills across or up
	 * @param area the bar's slot
	 * @param segments how many segments it is cut in
	 */
	public record Gauge(Kind kind, Decal area, int segments) {
		static Gauge of(ThemeData d, String prefix) {
			return new Gauge(Kind.values()[d.integer(prefix + "Kind", 0, Kind.values().length - 1)], Decal.of(d, prefix), d.integer(prefix + "Segments", 1, 64));
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
	 * One style's layout. The frame (when there is one) and the plate are nine-slice sprites; the others are fixed-size sprites placed by their decal. A decal with
	 * a width of 0 is off.
	 *
	 * @param name the style's slug, which names its sprite folder
	 * @param frame whether a frame is drawn over the whole screen (round 1's consoles); the compact styles have none
	 * @param compact whether the plate shows the tally on one line and the alerts, and the lamps and the cargo gauge are drawn; else it lists every line of the readout
	 * @param header a decal drawn after the gauges, under the altimeter
	 * @param partA a housing, drawn after the frame
	 * @param partB another housing
	 * @param partC a third housing
	 * @param cap drawn over the scanner's cells: the bezel that masks them
	 * @param fuel the fuel gauge
	 * @param hull the hull gauge
	 * @param depth the depth gauge
	 * @param heat the heat gauge
	 * @param cargo the cargo gauge
	 * @param lamps where the warning lamps go: {@link CockpitHud#LAMPS} lamps in a row (a slot wider than it is tall) or a column
	 * @param scanner where the scanner's cells go (and its title, under them)
	 * @param readout where the readout's plate hangs: over its anchor when that is the bottom edge, else under it
	 * @param altimeterY the altimeter's top, as the HUD theme's {@code altimeterMargin} is without a cockpit
	 */
	public record Layout(
			String name,
			boolean frame,
			boolean compact,
			Decal header,
			Decal partA,
			Decal partB,
			Decal partC,
			Decal cap,
			Gauge fuel,
			Gauge hull,
			Gauge depth,
			Gauge heat,
			Gauge cargo,
			Decal lamps,
			Decal scanner,
			Anchor readout,
			int altimeterY) {
		static Layout of(ThemeData d, Style style) {
			String p = style.slug();
			return new Layout(p, d.integer(p + "Frame", 0, 1) == 1, d.integer(p + "Compact", 0, 1) == 1, Decal.of(d, p + "Header"), Decal.of(d, p + "PartA"),
					Decal.of(d, p + "PartB"), Decal.of(d, p + "PartC"), Decal.of(d, p + "Cap"), Gauge.of(d, p + "Fuel"), Gauge.of(d, p + "Hull"), Gauge.of(d, p + "Depth"),
					Gauge.of(d, p + "Heat"), Gauge.of(d, p + "Cargo"), Decal.of(d, p + "Lamps"), Decal.of(d, p + "Scanner"), Anchor.of(d, p + "Readout"),
					d.integer(p + "AltimeterY", 0, 200));
		}

		/** The GUI sprite {@code slot} of this style: {@code frame}, {@code plate}, {@code header}, {@code part_a}, {@code part_b}, {@code part_c} or {@code cap}. */
		public Identifier sprite(String slot) {
			return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "cockpit/" + name + "/" + slot);
		}
	}

	/**
	 * The front camera's feed (#448, docs/design/cockpit-concepts-2.md): what it shows under each hazard. A pod with the camera sees the world through it and not through its
	 * own cab, until the feed fails.
	 *
	 * @param installed whether the local pod has the camera; a stand-in for the part, which a later build buys at the upgrade terminal
	 * @param staticPerHull the static a hit adds, for each share of the pod's most hull it takes
	 * @param staticFallPerTick how fast the static dies away, per client tick
	 * @param staticDots how many specks of static fill a frame at full static
	 * @param dotSize the side of a speck, in GUI pixels
	 * @param lensHeatPerTick how fast the lens heats toward the failing point while lava burns the hull, per client tick
	 * @param lensCoolPerTick how fast it cools
	 * @param lensDead the lens heat at which the feed fails
	 * @param lensRecover the lens heat at which a failed feed comes back
	 * @param flickerMaxChance the chance each tick that a drop-out starts, when the hull is gone
	 * @param flickerMinTicks the shortest drop-out
	 * @param flickerMaxTicks the longest drop-out
	 * @param hazeLines how many bands of heat haze cross the feed at full heat
	 * @param hazeAlpha the strength of the haze's tint at full heat, 0 to 255
	 * @param veilAlpha how dark the picture is under full static, 0 to 255
	 * @param dotAlphaMin the faintest speck of static, 0 to 255
	 * @param tearBands how many torn bands cross the picture at full static
	 * @param tearAlpha the strength of a torn band, 0 to 255
	 * @param staticColor a speck of static
	 * @param veilColor what the picture fades toward under heavy static
	 * @param hazeColor the heat haze
	 * @param liveColor the badge of a clear feed
	 * @param badgeStatic the static from which the badge is amber and not green
	 * @param badgeDot the side of the badge's lamp
	 * @param badgeGap the space between the lamp and its word
	 * @param badge where the feed's badge sits
	 */
	public record CameraLook(
			boolean installed,
			double staticPerHull,
			double staticFallPerTick,
			int staticDots,
			int dotSize,
			double lensHeatPerTick,
			double lensCoolPerTick,
			double lensDead,
			double lensRecover,
			double flickerMaxChance,
			int flickerMinTicks,
			int flickerMaxTicks,
			int hazeLines,
			int hazeAlpha,
			int veilAlpha,
			int dotAlphaMin,
			int tearBands,
			int tearAlpha,
			int staticColor,
			int veilColor,
			int hazeColor,
			int liveColor,
			double badgeStatic,
			int badgeDot,
			int badgeGap,
			Anchor badge) {
		static CameraLook of(ThemeData d) {
			double dead = d.decimal("cameraLensDead", 0.1, 1);
			double recover = d.decimal("cameraLensRecover", 0, 1);
			if (recover >= dead) {
				throw new IllegalArgumentException(d.conflict("cameraLensRecover " + recover + " must be under cameraLensDead " + dead + ", or a failed feed never comes back",
						"cameraLensRecover").getMessage());
			}
			int minTicks = d.integer("cameraFlickerMinTicks", 1, 200);
			int maxTicks = d.integer("cameraFlickerMaxTicks", 1, 200);
			if (minTicks > maxTicks) {
				throw new IllegalArgumentException(d.conflict("cameraFlickerMinTicks " + minTicks + " must not be over cameraFlickerMaxTicks " + maxTicks,
						"cameraFlickerMinTicks").getMessage());
			}
			return new CameraLook(d.integer("cameraInstalled", 0, 1) == 1, d.decimal("cameraStaticPerHull", 0, 100), d.decimal("cameraStaticFallPerTick", 0.001, 1),
					d.integer("cameraStaticDots", 0, 20000), d.integer("cameraDotSize", 1, 8), d.decimal("cameraLensHeatPerTick", 0.001, 1),
					d.decimal("cameraLensCoolPerTick", 0.001, 1), dead, recover, d.decimal("cameraFlickerMaxChance", 0, 1), minTicks, maxTicks,
					d.integer("cameraHazeLines", 0, 64), d.integer("cameraHazeAlpha", 0, 255), d.integer("cameraVeilAlpha", 0, 255),
					d.integer("cameraDotAlphaMin", 0, 255), d.integer("cameraTearBands", 0, 32), d.integer("cameraTearAlpha", 0, 255), d.color("cameraStaticColor"),
					d.color("cameraVeilColor"), d.color("cameraHazeColor"), d.color("cameraLiveColor"), d.decimal("cameraBadgeStatic", 0, 1),
					d.integer("cameraBadgeDot", 1, 16), d.integer("cameraBadgeGap", 0, 16), Anchor.of(d, "cameraBadge"));
		}
	}

	public static CockpitLook current() {
		return UiTheme.current().cockpit();
	}

	/** The layout of the style in force, or empty when the style is 0. */
	public Optional<Layout> active() {
		return switch (style) {
			case OFF -> Optional.empty();
			case CONSOLES -> Optional.of(consoles);
			case CORNERS -> Optional.of(corners);
			case RIBBON -> Optional.of(ribbon);
			case POSTS -> Optional.of(posts);
			case CAB -> Optional.of(cab);
		};
	}

	public static CockpitLook of(ThemeData d) {
		return new CockpitLook(Style.values()[d.integer("style", 0, Style.values().length - 1)], Layout.of(d, Style.CONSOLES), Layout.of(d, Style.CORNERS),
				Layout.of(d, Style.RIBBON), Layout.of(d, Style.POSTS), Layout.of(d, Style.CAB), d.color("fuelColor"), d.color("hullColor"), d.color("depthColor"),
				d.color("heatColor"), d.color("cargoColor"), d.color("lowColor"), d.color("offColor"), d.decimal("hullLowFraction", 0, 1),
				d.decimal("heatRisePerTick", 0.001, 1), d.decimal("heatFallPerTick", 0.001, 1), d.integer("platePad", 0, 16), d.decimal("heatWarning", 0, 1),
				d.integer("scannerTitleGap", 0, 16), d.integer("lampGap", 0, 8), d.integer("tallyGap", 0, 16), CameraLook.of(d));
	}
}
