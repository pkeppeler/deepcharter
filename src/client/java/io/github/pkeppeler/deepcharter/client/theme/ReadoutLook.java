package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How the block readout just above the hotbar looks ({@code theme/readout.json}, #859, #1261): two centred lines, the
 * block's name and a hint, in the account line's green and the lining line's amber. Lengths are GUI pixels, timings are
 * client ticks (20 are one second).
 *
 * <p>The block readout has a room of two lines that ends {@code bottomOffset} above the bottom of the GUI, so the same
 * number of pixels clears vanilla's rows at every GUI scale. Its name is always on the room's first line and its hint,
 * when there is one, on the second, so a sweep of the view moves nothing. The placement readout stands above the room.
 *
 * @param bottomOffset space between the bottom of the GUI and the block readout's room; never less than {@link
 *     #CLEAR_OF_VANILLA}
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
     * The least {@code bottomOffset}: the top of the backdrop of vanilla's highest centred line above the hotbar, the
     * action bar, drawn at {@code guiHeight - 72} with two pixels of backdrop (the held item's name sits lower, at
     * {@code guiHeight - 59}). A pack cannot lower the readout onto them.
     */
    public static final int CLEAR_OF_VANILLA = 74;

    public static ReadoutLook current() {
        return UiTheme.current().readout();
    }

    /** The y of the bottom edge of the room the block readout reserves on a GUI {@code guiHeight} high. */
    public int blockBottom(int guiHeight) {
        return guiHeight - bottomOffset;
    }

    /**
     * The y of the block readout's name line, for a font {@code lineHeight} high: the top of the room of two lines
     * above {@link #blockBottom}, whether or not there is a hint under the name, so the name never moves between
     * blocks. Never above the screen.
     */
    // const-param: callers pass font.lineHeight, which javac folds to 9 (a constant final field in 26.3's Font); the
    // parameter keeps the layout tied to the font, and tests pass it too
    public int blockTop(int guiHeight, int lineHeight) {
        return Math.max(0, blockBottom(guiHeight) - (int) Math.ceil((2 * lineHeight + lineGap) * scale));
    }

    /**
     * The y of the bottom edge of the placement readout, for a font {@code lineHeight} high: above the block readout's
     * two lines and a gap, so that both can show at once and neither moves the other.
     */
    private int placementBottom(int guiHeight, int lineHeight) {
        return blockBottom(guiHeight) - (int) Math.ceil((2 * lineHeight + 2 * lineGap) * scale);
    }

    /**
     * The y of the top of the placement readout's box {@code boxHeight} high, growing up from {@link #placementBottom};
     * never above the screen.
     */
    // const-param: callers pass font.lineHeight, which javac folds to 9 (a constant final field in 26.3's Font); the
    // parameter keeps the layout tied to the font, and tests pass it too
    public int placementTop(int guiHeight, int lineHeight, int boxHeight) {
        return Math.max(0, placementBottom(guiHeight, lineHeight) - boxHeight);
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
