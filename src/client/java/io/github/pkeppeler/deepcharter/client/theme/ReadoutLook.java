package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How the block readout just above the hotbar looks ({@code theme/readout.json}, #859, #1261): two centred lines, the block's name and a hint,
 * in the account line's green and the lining line's amber. Lengths are GUI pixels, timings are client ticks (20 are one second).
 *
 * <p>The readouts stand as one stack whose bottom edge is {@code bottomOffset} above the bottom of the GUI, so the same number of pixels clears
 * vanilla's rows at every GUI scale. The block readout takes the stack's first two lines and the placement readout the last two.
 *
 * @param bottomOffset space between the bottom of the GUI and the stack's bottom edge; never less than {@link #CLEAR_OF_VANILLA}
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
        int bottomOffset,
        int lineGap,
        double scale,
        int nameColor,
        int hintColor,
        int fadeInTicks,
        int fadeOutTicks,
        int settleTicks) {
    /** The longest a fade or settle may take, so a pack cannot leave a readout hanging or hide it for long. */
    static final int MAX_TICKS = 40;

    /**
     * The least {@code bottomOffset}: the top of the backdrop of vanilla's highest centred line above the hotbar, the action bar, drawn at
     * {@code guiHeight - 72} with two pixels of backdrop (the held item's name sits lower, at {@code guiHeight - 59}). A pack cannot lower the
     * readout onto them.
     */
    public static final int CLEAR_OF_VANILLA = 74;

    /** The lines the placement readout starts under the stack's top: the block readout takes the first two. */
    private static final int PLACEMENT_LINES_BELOW = 2;

    public static ReadoutLook current() {
        return UiTheme.current().readout();
    }

    /** Where the placement readout starts below the stack's top, in GUI pixels, for a font {@code lineHeight} high. */
    private int placementDrop(int lineHeight) {
        return (int) Math.ceil(PLACEMENT_LINES_BELOW * (lineHeight + lineGap) * (float) scale);
    }

    /** The y of the stack's top on a GUI {@code guiHeight} high: the bottom edge is {@link #bottomOffset} up, and the stack grows upward from it. */
    public int stackTop(int guiHeight, int lineHeight) {
        int step = lineHeight + lineGap;
        int below = placementDrop(lineHeight) + (int) Math.ceil((lineHeight + step) * (float) scale);
        return guiHeight - bottomOffset - below;
    }

    /** The y of the placement readout's top, {@value #PLACEMENT_LINES_BELOW} lines under the stack's top. */
    public int placementTop(int guiHeight, int lineHeight) {
        return stackTop(guiHeight, lineHeight) + placementDrop(lineHeight);
    }

    public static ReadoutLook of(ThemeData d) {
        return new ReadoutLook(
                d.integer("bottomOffset", CLEAR_OF_VANILLA, 200),
                d.integer("lineGap", 0, 20),
                d.decimal("scale", 0.5, 2.0),
                d.color("nameColor"),
                d.color("hintColor"),
                d.integer("fadeInTicks", 1, MAX_TICKS),
                d.integer("fadeOutTicks", 1, MAX_TICKS),
                d.integer("settleTicks", 0, MAX_TICKS));
    }
}
