package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import java.util.List;

/**
 * Where vanilla's HUD draws on a GUI of a given size for a survival player on foot, from the 26.3 {@code Hud}'s own figures, so a test can
 * check that a HUD element a PR adds stays clear of it. {@link HudKeepClear} lists only the mod's own parts.
 *
 * <p>The centred lines (the action bar, the held item's name, the title and the subtitle) are as wide as their text, which can be the whole
 * GUI, so each is a full-width row; each includes the two pixels of backdrop {@code textWithBackdrop} draws when the text background is on.
 * The hotbar row leaves room for the off-hand slot and the hotbar attack indicator on either side. The status rows (experience, health,
 * food, armour and air) are one block, one row of hearts high: extra rows of hearts, and a ridden animal's hearts, draw higher.
 */
public final class VanillaHudRows {
    /** Half the hotbar's width. */
    private static final int HOTBAR_HALF = 91;
    /** The off-hand slot, or the hotbar attack indicator, beside the hotbar. */
    private static final int BESIDE_HOTBAR = 29;
    /** The backdrop around a centred line. */
    private static final int BACKDROP = 2;

    private static final int LINE = 9;

    private VanillaHudRows() {}

    /** Every row vanilla draws in on a GUI {@code guiWidth} x {@code guiHeight}. */
    public static List<HudKeepClear.Rect> rects(int guiWidth, int guiHeight) {
        int centre = guiWidth / 2;
        int middle = guiHeight / 2;
        return List.of(
                // The hotbar is 22 high, its selection frame 23.
                new HudKeepClear.Rect(
                        "hotbar",
                        centre - HOTBAR_HALF - BESIDE_HOTBAR,
                        guiHeight - 23,
                        2 * (HOTBAR_HALF + BESIDE_HOTBAR),
                        23),
                // Hearts and food at guiHeight - 39, armour and air a row of 10 above them.
                new HudKeepClear.Rect("status rows", centre - HOTBAR_HALF, guiHeight - 49, 2 * HOTBAR_HALF, 26),
                centredRow("held item name", guiWidth, guiHeight - 59, 1),
                // Drawn at guiHeight - 68, its text 4 above that.
                centredRow("action bar", guiWidth, guiHeight - 72, 1),
                // Scaled 4 from the middle, its text 10 above it.
                centredRow("title", guiWidth, middle - 4 * 10, 4),
                // Scaled 2 from the middle, its text 5 below it.
                centredRow("subtitle", guiWidth, middle + 2 * 5, 2));
    }

    private static HudKeepClear.Rect centredRow(String what, int guiWidth, int textTop, int scale) {
        return new HudKeepClear.Rect(what, 0, textTop - scale * BACKDROP, guiWidth, scale * (LINE + 2 * BACKDROP));
    }
}
