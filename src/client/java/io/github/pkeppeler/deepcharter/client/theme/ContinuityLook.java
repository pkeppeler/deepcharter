package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How a Continuity Event looks and sounds to the person it happens to ({@code theme/continuity.json}, #1337): the cut, the record skipping
 * on their last sound, and the skull of the Company's own words that laughs at them. Colours are ARGB; lengths are GUI pixels; times are
 * seconds. The skull's phosphor and glow are the {@code crt} area's.
 *
 * @param cutSeconds          black silence from the death to the skull and the first sound
 * @param arrivalSeconds      the skull tears in from static over this long
 * @param skipPeriodSeconds   the first pass of the skipping record; each pass after it is {@code skipShrink} as long
 * @param skipShrink          how much shorter each pass is than the one before
 * @param skipPasses          passes of the last sound before the laugh
 * @param skipPitch           each pass plays at this times the pitch of the one before: slightly lower
 * @param skipOverlapSeconds  the laugh starts this long before the last pass ends; shorter than the last pass
 * @param skipLoudness        how loud each pass reads to the skull, 0 to 1, at its start
 * @param skipDecaySeconds    how fast a pass's loudness dies away for the skull
 * @param replaySeconds       how far back the last sound is looked for; nothing older is replayed
 * @param loudVolume          a sound played at this volume or more counts as loud: the latest loud one is replayed
 * @param fadeSeconds         the skull fades out over the last this-long of the laugh
 * @param rows                rows of letters from the top of the skull to the bottom of the bones
 * @param boxFraction         the skull's square, as a share of the screen's height
 * @param cellAspect          a letter's cell, its width over its height: under 1 the letters stand narrow and packed, as on a terminal
 * @param glyphFill           a line of the terminal font is drawn this many times a cell's height: the letters' height in their cell
 * @param holeMargin          the eye sockets, the nose and the open mouth are cut this share of a cell wider all round, so they stay
 *                            dark and empty
 * @param jawSteps            jaw positions the skull is worked out for; the laugh's loudness picks the nearest
 * @param loudGain            the brightest the laugh makes the picture, on top of its flicker
 * @param bloomLayers         layers of the soft glow under the letters; 0 draws none
 * @param bloomSpread         each layer of the glow reaches this much further out than the one inside it
 * @param bloomColor          one layer of the glow; the layers add up where they overlap
 * @param haloPixels          the halo of the crt glow colour round each letter reaches this far out, in GUI pixels (a fraction draws
 *                            finer on a scaled GUI)
 * @param tearRows            rows torn sideways at the loudest
 * @param tearPixels          how far a row is torn at the loudest
 * @param shuffleLoudness     above this loudness the letters shuffle each frame
 * @param flicker             the brightness flickers this much, up and down
 * @param bandSpeed           the rolling band's speed down the screen, pixels a second
 * @param bandHeight          the rolling band's height
 * @param bandColor           the rolling band at its brightest
 * @param boneColor           the letters of the crossbones, a shade dimmer than the skull's
 * @param outlineColor        the faint line round the skull and the bones
 * @param staticColor         the static the skull tears in from
 * @param buttonWidth         RETURN TO WORK's width
 * @param buttonHeight        RETURN TO WORK's height
 * @param smallButtonWidth    DISCONNECT's width
 * @param smallButtonHeight   DISCONNECT's height
 * @param buttonGap           the gap between the two
 * @param bodySolidTicks      what the crew see: a dying person's body stands still and solid for this many of vanilla's twenty dying ticks,
 *                            then is drawn see-through, with nothing it wears or holds, until vanilla's puff removes it
 */
public record ContinuityLook(
        double cutSeconds,
        double arrivalSeconds,
        double skipPeriodSeconds,
        double skipShrink,
        int skipPasses,
        double skipPitch,
        double skipOverlapSeconds,
        double skipLoudness,
        double skipDecaySeconds,
        double replaySeconds,
        double loudVolume,
        double fadeSeconds,
        int rows,
        double boxFraction,
        double cellAspect,
        double glyphFill,
        double holeMargin,
        int jawSteps,
        double loudGain,
        int bloomLayers,
        int bloomSpread,
        int bloomColor,
        double haloPixels,
        int tearRows,
        int tearPixels,
        double shuffleLoudness,
        double flicker,
        int bandSpeed,
        int bandHeight,
        int bandColor,
        int boneColor,
        int outlineColor,
        int staticColor,
        int buttonWidth,
        int buttonHeight,
        int smallButtonWidth,
        int smallButtonHeight,
        int buttonGap,
        double bodySolidTicks) {
    public static ContinuityLook current() {
        return UiTheme.current().continuity();
    }

    public static ContinuityLook of(ThemeData d) {
        double period = d.decimal("skipPeriodSeconds", 0.05, 1.0);
        double shrink = d.decimal("skipShrink", 0.5, 1.0);
        int passes = d.integer("skipPasses", 0, 6);
        double overlap = d.decimal("skipOverlapSeconds", 0.0, 0.5);
        // The laugh overlaps the last pass, the shortest: each pass is skipShrink as long as the one before.
        if (passes > 0 && overlap >= period * Math.pow(shrink, passes - 1)) {
            throw d.conflict(
                    "the laugh cannot start before the last pass does",
                    "skipOverlapSeconds",
                    "skipPeriodSeconds",
                    "skipShrink",
                    "skipPasses");
        }
        return new ContinuityLook(
                d.decimal("cutSeconds", 0.0, 2.0),
                d.decimal("arrivalSeconds", 0.0, 2.0),
                period,
                shrink,
                passes,
                d.decimal("skipPitch", 0.5, 1.0),
                overlap,
                d.decimal("skipLoudness", 0.0, 1.0),
                d.decimal("skipDecaySeconds", 0.01, 1.0),
                d.decimal("replaySeconds", 0.5, 10.0),
                d.decimal("loudVolume", 0.0, 1.0),
                d.decimal("fadeSeconds", 0.0, 2.0),
                d.integer("rows", 12, 120),
                d.decimal("boxFraction", 0.3, 1.0),
                d.decimal("cellAspect", 0.3, 1.5),
                d.decimal("glyphFill", 0.5, 3.0),
                d.decimal("holeMargin", 0.0, 1.0),
                d.integer("jawSteps", 1, 64),
                d.decimal("loudGain", 0.0, 0.5),
                d.integer("bloomLayers", 0, 8),
                d.integer("bloomSpread", 0, 16),
                d.color("bloomColor"),
                d.decimal("haloPixels", 0.0, 3.0),
                d.integer("tearRows", 0, 40),
                d.integer("tearPixels", 0, 60),
                d.decimal("shuffleLoudness", 0.0, 1.0),
                d.decimal("flicker", 0.0, 0.5),
                d.integer("bandSpeed", 0, 1000),
                d.integer("bandHeight", 0, 200),
                d.color("bandColor"),
                d.color("boneColor"),
                d.color("outlineColor"),
                d.color("staticColor"),
                d.integer("buttonWidth", 40, 300),
                d.integer("buttonHeight", 10, 60),
                d.integer("smallButtonWidth", 20, 300),
                d.integer("smallButtonHeight", 8, 60),
                d.integer("buttonGap", 0, 60),
                d.decimal("bodySolidTicks", 0.0, 20.0));
    }
}
