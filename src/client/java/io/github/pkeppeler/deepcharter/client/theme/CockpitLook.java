package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook.Decal;
import io.github.pkeppeler.deepcharter.theme.ThemeData;
import net.minecraft.resources.Identifier;

/**
 * The cockpit HUD a pilot sees from the seat: the Ribbon ({@code theme/cockpit.json}, #247, docs/design/cockpit-concepts-2.md, the user's pick of 2026-10-11). One slim
 * ribbon along the top edge holds a bar for each of fuel, hull, cargo, depth and heat, the altimeter in a recess at its middle and a row of warning lamps at its end;
 * the scanner hangs from the ribbon's right end and the readout plate (the tally and every other line of the readout) under it. Its sprites are GUI sprites under
 * {@code textures/gui/sprites/cockpit/ribbon/} ({@link Layout#sprite}); the gauges only draw what moves, on the bar the sprites left room for. A pack restyles the
 * colours and the places, or turns the cockpit off ({@code enabled} 0) to get the plain text HUD back. Colours are ARGB, lengths are GUI pixels.
 *
 * @param enabled whether the cockpit is drawn while the pilot sits in a pod and the GUI is big enough ({@code minGuiWidth}, {@code minGuiHeight})
 * @param minGuiWidth the narrowest GUI the ribbon fits, in GUI pixels; under it the plain HUD is drawn
 * @param minGuiHeight the lowest GUI the ribbon, the scanner under it and the plate fit
 * @param ribbon where each piece of the ribbon sits
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
 * @param plateGap the space between the inside of the readout plate sprite's border and its text, on each side
 * @param heatWarning the heat from which the heat gauge is in warning
 * @param scannerTitleGap the space between the scanner window and its title under it
 * @param lampGap the space between two warning lamps
 * @param tallyGap the space between two numbers of the plate's tally
 * @param camera the front camera's feed (#448)
 * @param decay what the signal decay (#502) does to the instruments
 */
public record CockpitLook(
        boolean enabled,
        int minGuiWidth,
        int minGuiHeight,
        Layout ribbon,
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
        int plateGap,
        double heatWarning,
        int scannerTitleGap,
        int lampGap,
        int tallyGap,
        CameraLook camera,
        DecayLook decay) {
    /** How a bar fills: segments lit from its start across, or up. */
    public enum Kind {
        ACROSS,
        UP
    }

    /**
     * One gauge: where it sits and how it reads. A width of 0 means the pack has no such gauge.
     *
     * @param kind a bar that fills across or up
     * @param area the bar's slot
     * @param segments how many segments it is cut in
     */
    public record Gauge(Kind kind, Decal area, int segments) {
        static Gauge of(ThemeData d, String prefix) {
            return new Gauge(
                    Kind.values()[d.integer(prefix + "Kind", 0, Kind.values().length - 1)],
                    Decal.of(d, prefix),
                    d.integer(prefix + "Segments", 1, 64));
        }

        public boolean on() {
            return area.on();
        }
    }

    /** A point put at a corner, an edge or the centre of the screen and moved by an offset; the box that hangs from it is as big as its content. */
    public record Anchor(int anchorX, int anchorY, int x, int y) {
        static Anchor of(ThemeData d, String prefix) {
            return new Anchor(
                    d.integer(prefix + "AnchorX", 0, 2),
                    d.integer(prefix + "AnchorY", 0, 2),
                    d.integer(prefix + "X", -1024, 1024),
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
     * The Ribbon's layout. The plate is a nine-slice sprite; the others are fixed-size sprites placed by their decal.
     *
     * @param band the ribbon along the top edge, with the faces of the gauges, the altimeter's recess and the lamps' slot
     * @param cap drawn over the scanner's cells: the bezel that masks them
     * @param fuel the fuel gauge
     * @param hull the hull gauge
     * @param depth the depth gauge
     * @param heat the heat gauge
     * @param cargo the cargo gauge
     * @param lamps where the warning lamps go: {@link CockpitHud#LAMPS} lamps in a row (a slot wider than it is tall) or a column
     * @param scanner where the scanner's cells go (and its title, under them)
     * @param readout where the readout's plate hangs: under the anchor
     * @param altimeterY the altimeter's top, as the HUD theme's {@code altimeterMargin} is without a cockpit
     */
    public record Layout(
            Decal band,
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
        private static final String P = "ribbon";

        static Layout of(ThemeData d) {
            return new Layout(
                    Decal.of(d, P + "PartA"),
                    Decal.of(d, P + "Cap"),
                    Gauge.of(d, P + "Fuel"),
                    Gauge.of(d, P + "Hull"),
                    Gauge.of(d, P + "Depth"),
                    Gauge.of(d, P + "Heat"),
                    Gauge.of(d, P + "Cargo"),
                    Decal.of(d, P + "Lamps"),
                    Decal.of(d, P + "Scanner"),
                    Anchor.of(d, P + "Readout"),
                    d.integer(P + "AltimeterY", 0, 200));
        }

        /** The GUI sprite {@code slot} of the Ribbon: {@code plate}, {@code part_a} or {@code cap}. */
        public Identifier sprite(String slot) {
            return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "cockpit/" + P + "/" + slot);
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
     * @param badgePad the space between the badge and the edge of its backing plate
     * @param badgeBack the badge's backing plate, so the badge reads over any wall
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
            int badgePad,
            int badgeBack,
            Anchor badge) {
        /** {@code color}'s red, green and blue with the opacity {@code alpha}, 0 to 255. */
        public static int withAlpha(int alpha, int color) {
            return alpha << 24 | (color & 0xFFFFFF);
        }

        static CameraLook of(ThemeData d) {
            double dead = d.decimal("cameraLensDead", 0.1, 1);
            double recover = d.decimal("cameraLensRecover", 0, 1);
            if (recover >= dead) {
                throw new IllegalArgumentException(d.conflict(
                                "cameraLensRecover " + recover + " must be under cameraLensDead " + dead
                                        + ", or a failed feed never comes back",
                                "cameraLensRecover")
                        .getMessage());
            }
            int minTicks = d.integer("cameraFlickerMinTicks", 1, 200);
            int maxTicks = d.integer("cameraFlickerMaxTicks", 1, 200);
            if (minTicks > maxTicks) {
                throw new IllegalArgumentException(d.conflict(
                                "cameraFlickerMinTicks " + minTicks + " must not be over cameraFlickerMaxTicks "
                                        + maxTicks,
                                "cameraFlickerMinTicks")
                        .getMessage());
            }
            return new CameraLook(
                    d.integer("cameraInstalled", 0, 1) == 1,
                    d.decimal("cameraStaticPerHull", 0, 100),
                    d.decimal("cameraStaticFallPerTick", 0.001, 1),
                    d.integer("cameraStaticDots", 0, 20000),
                    d.integer("cameraDotSize", 1, 8),
                    d.decimal("cameraLensHeatPerTick", 0.001, 1),
                    d.decimal("cameraLensCoolPerTick", 0.001, 1),
                    dead,
                    recover,
                    d.decimal("cameraFlickerMaxChance", 0, 1),
                    minTicks,
                    maxTicks,
                    d.integer("cameraHazeLines", 0, 64),
                    d.integer("cameraHazeAlpha", 0, 255),
                    d.integer("cameraVeilAlpha", 0, 255),
                    d.integer("cameraDotAlphaMin", 0, 255),
                    d.integer("cameraTearBands", 0, 32),
                    d.integer("cameraTearAlpha", 0, 255),
                    d.color("cameraStaticColor"),
                    d.color("cameraVeilColor"),
                    d.color("cameraHazeColor"),
                    d.color("cameraLiveColor"),
                    d.decimal("cameraBadgeStatic", 0, 1),
                    d.integer("cameraBadgeDot", 1, 16),
                    d.integer("cameraBadgeGap", 0, 16),
                    d.integer("cameraBadgePad", 0, 8),
                    d.color("cameraBadgeBack"),
                    Anchor.of(d, "cameraBadge"));
        }
    }

    /**
     * The signal decay's look and timing (#502, {@code SignalDecay}): which instrument fails when is gameplay, how it looks and how long one fault lasts is the theme's.
     *
     * @param cellTicks the span of client ticks an instrument keeps one fault: a flicker, a false reading or a drop-out lasts this long, at least
     * @param deadColor what fills a dark gauge or scanner window
     * @param baseNoise the share of the full static that the scanner window always has while the signal decays and the scanner is not jumping, 0 to 1
     * @param staticDots how many specks of static cover the scanner window at full decay
     * @param dotSize the side of a speck, in GUI pixels
     * @param dotAlphaMin the faintest speck, 0 to 255
     * @param staticColor a speck, and a torn band
     * @param tearBands how many torn bands cross the scanner window at full decay
     * @param tearAlpha the strength of a torn band, 0 to 255
     * @param tagColor the tag that stands in for a dark readout plate while the signal is failing
     * @param lostColor the tag in the blackout
     * @param cueSlowTicks the ticks between crackles at the first slab of decay
     * @param cueFastTicks the ticks between crackles at full decay
     * @param alarmTicks the ticks between the hull alarm's beeps, which sounds while the decay is on and the hull is low
     */
    public record DecayLook(
            int cellTicks,
            int deadColor,
            double baseNoise,
            int staticDots,
            int dotSize,
            int dotAlphaMin,
            int staticColor,
            int tearBands,
            int tearAlpha,
            int tagColor,
            int lostColor,
            int cueSlowTicks,
            int cueFastTicks,
            int alarmTicks) {
        static DecayLook of(ThemeData d) {
            int slow = d.integer("decayCueSlowTicks", 1, 1200);
            int fast = d.integer("decayCueFastTicks", 1, 1200);
            if (fast > slow) {
                throw new IllegalArgumentException(d.conflict(
                                "decayCueFastTicks " + fast + " must not be over decayCueSlowTicks " + slow
                                        + ", or the crackle slows as the decay deepens",
                                "decayCueFastTicks")
                        .getMessage());
            }
            return new DecayLook(
                    d.integer("decayCellTicks", 1, 100),
                    d.color("decayDeadColor"),
                    d.decimal("decayBaseNoise", 0, 1),
                    d.integer("decayStaticDots", 0, 5000),
                    d.integer("decayDotSize", 1, 8),
                    d.integer("decayDotAlphaMin", 0, 255),
                    d.color("decayStaticColor"),
                    d.integer("decayTearBands", 0, 32),
                    d.integer("decayTearAlpha", 0, 255),
                    d.color("decayTagColor"),
                    d.color("decayLostColor"),
                    slow,
                    fast,
                    d.integer("decayAlarmTicks", 1, 1200));
        }
    }

    public static CockpitLook current() {
        return UiTheme.current().cockpit();
    }

    /** Whether the cockpit is drawn on a GUI {@code guiWidth} x {@code guiHeight}: it is enabled and the Ribbon fits. */
    public boolean fits(int guiWidth, int guiHeight) {
        return enabled && guiWidth >= minGuiWidth && guiHeight >= minGuiHeight;
    }

    public static CockpitLook of(ThemeData d) {
        return new CockpitLook(
                d.integer("enabled", 0, 1) == 1,
                d.integer("minGuiWidth", 1, 4096),
                d.integer("minGuiHeight", 1, 4096),
                Layout.of(d),
                d.color("fuelColor"),
                d.color("hullColor"),
                d.color("depthColor"),
                d.color("heatColor"),
                d.color("cargoColor"),
                d.color("lowColor"),
                d.color("offColor"),
                d.decimal("hullLowFraction", 0, 1),
                d.decimal("heatRisePerTick", 0.001, 1),
                d.decimal("heatFallPerTick", 0.001, 1),
                d.integer("plateGap", 0, 16),
                d.decimal("heatWarning", 0, 1),
                d.integer("scannerTitleGap", 0, 16),
                d.integer("lampGap", 0, 8),
                d.integer("tallyGap", 0, 16),
                CameraLook.of(d),
                DecayLook.of(d));
    }
}
