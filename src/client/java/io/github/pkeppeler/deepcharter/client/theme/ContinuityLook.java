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
 * @param skipOverlapSeconds  the laugh starts this long before the last pass ends
 * @param skipLoudness        how loud each pass reads to the skull, 0 to 1, at its start
 * @param skipDecaySeconds    how fast a pass's loudness dies away for the skull
 * @param replaySeconds       how far back the last sound is looked for; nothing older is replayed
 * @param loudVolume          a sound played at this volume or more counts as loud: the latest loud one is replayed
 * @param fadeSeconds         the skull fades out over the last this-long of the laugh
 * @param rows                rows of letters from the top of the skull to the bottom of the bones
 * @param boxFraction         the skull's square, as a share of the screen's height
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
        int tearRows,
        int tearPixels,
        double shuffleLoudness,
        double flicker,
        int bandSpeed,
        int bandHeight,
        int bandColor,
        int boneColor,
        int outlineColor,
        int staticColor) {
    public static ContinuityLook current() {
        return UiTheme.current().continuity();
    }

    public static ContinuityLook of(ThemeData d) {
        double period = d.decimal("skipPeriodSeconds", 0.05, 1.0);
        double overlap = d.decimal("skipOverlapSeconds", 0.0, 0.5);
        if (overlap >= period) {
            throw d.conflict(
                    "the laugh cannot start before the last pass does", "skipOverlapSeconds", "skipPeriodSeconds");
        }
        return new ContinuityLook(
                d.decimal("cutSeconds", 0.0, 2.0),
                d.decimal("arrivalSeconds", 0.0, 2.0),
                period,
                d.decimal("skipShrink", 0.5, 1.0),
                d.integer("skipPasses", 0, 6),
                d.decimal("skipPitch", 0.5, 1.0),
                overlap,
                d.decimal("skipLoudness", 0.0, 1.0),
                d.decimal("skipDecaySeconds", 0.01, 1.0),
                d.decimal("replaySeconds", 0.5, 10.0),
                d.decimal("loudVolume", 0.0, 1.0),
                d.decimal("fadeSeconds", 0.0, 2.0),
                d.integer("rows", 12, 120),
                d.decimal("boxFraction", 0.3, 1.0),
                d.integer("tearRows", 0, 40),
                d.integer("tearPixels", 0, 60),
                d.decimal("shuffleLoudness", 0.0, 1.0),
                d.decimal("flicker", 0.0, 0.5),
                d.integer("bandSpeed", 0, 1000),
                d.integer("bandHeight", 0, 200),
                d.color("bandColor"),
                d.color("boneColor"),
                d.color("outlineColor"),
                d.color("staticColor"));
    }
}
