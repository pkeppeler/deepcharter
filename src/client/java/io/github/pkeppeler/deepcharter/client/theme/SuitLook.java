package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How the suit gauge looks ({@code theme/suit.json}, #993): a line of text, a segmented bar and the axes under it, at the top left.
 * Colours are ARGB; lengths are GUI pixels.
 *
 * @param leftMargin   space between the gauge and the screen's left edge
 * @param topMargin    space between the gauge's top line and the screen's top edge: enough to clear the altimeter at the top centre, with
 *                     its jitter, on the narrowest GUI
 * @param barWidth     the bar's width
 * @param barHeight    the bar's height
 * @param segments     how many cells the bar is cut into
 * @param segmentGap   the gap between two cells
 * @param lineGap      the gap between the text and the bar, and the bar and the axes line
 * @param okColor      a suit that holds or refills
 * @param amberColor   a suit with half a minute left
 * @param redColor     a suit with ten seconds left, a breach, and a log that cannot be read
 * @param dimColor     a suit at rest, and the empty cells
 * @param blinkTicks   how long the bar shows and hides while it blinks
 */
public record SuitLook(
        int leftMargin,
        int topMargin,
        int barWidth,
        int barHeight,
        int segments,
        int segmentGap,
        int lineGap,
        int okColor,
        int amberColor,
        int redColor,
        int dimColor,
        int blinkTicks) {
    public static SuitLook current() {
        return UiTheme.current().suit();
    }

    public static SuitLook of(ThemeData d) {
        int barWidth = d.integer("barWidth", 20, 200);
        int segments = d.integer("segments", 2, 40);
        int segmentGap = d.integer("segmentGap", 0, 4);
        if (barWidth < segments * (1 + segmentGap)) {
            throw d.conflict(
                    "the bar is too narrow for its cells and their gaps", "barWidth", "segments", "segmentGap");
        }
        return new SuitLook(
                d.integer("leftMargin", 0, 100),
                d.integer("topMargin", 0, 100),
                barWidth,
                d.integer("barHeight", 2, 20),
                segments,
                segmentGap,
                d.integer("lineGap", 0, 10),
                d.color("okColor"),
                d.color("amberColor"),
                d.color("redColor"),
                d.color("dimColor"),
                d.integer("blinkTicks", 1, 40));
    }
}
