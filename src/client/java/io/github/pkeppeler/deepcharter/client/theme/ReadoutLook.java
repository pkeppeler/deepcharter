package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How the block readout under the crosshair looks ({@code theme/readout.json}, #859): two centred lines, the block's name and a hint, in the
 * account line's green and the lining line's amber. Lengths are GUI pixels, timings are client ticks (20 are one second).
 *
 * @param offsetY space between the screen's centre (the crosshair) and the name line's top
 * @param lineGap space between the name and the hint, on top of the font height
 * @param scale how large the text is drawn, as a multiple of the font
 * @param nameColor the block's name
 * @param hintColor the hint under it
 * @param fadeInTicks ticks a new readout takes to fade in
 * @param fadeOutTicks ticks a readout takes to fade out when the crosshair leaves it
 * @param settleTicks ticks the crosshair must rest on a different block before its readout replaces the one on screen, so a sweep of the
 *     view across many blocks does not flicker
 */
public record ReadoutLook(
        int offsetY,
        int lineGap,
        double scale,
        int nameColor,
        int hintColor,
        int fadeInTicks,
        int fadeOutTicks,
        int settleTicks) {
    /** The longest a fade or settle may take, so a pack cannot leave a readout hanging or hide it for long. */
    static final int MAX_TICKS = 40;

    public static ReadoutLook current() {
        return UiTheme.current().readout();
    }

    public static ReadoutLook of(ThemeData d) {
        return new ReadoutLook(
                d.integer("offsetY", 0, 200),
                d.integer("lineGap", 0, 20),
                d.decimal("scale", 0.5, 2.0),
                d.color("nameColor"),
                d.color("hintColor"),
                d.integer("fadeInTicks", 1, MAX_TICKS),
                d.integer("fadeOutTicks", 1, MAX_TICKS),
                d.integer("settleTicks", 0, MAX_TICKS));
    }
}
