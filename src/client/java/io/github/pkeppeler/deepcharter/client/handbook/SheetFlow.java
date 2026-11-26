package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Splits the body of a page that flows (a chapter's text, a page of Appendix A) into sheets: each sheet takes the lines
 * that fit above Back and Next, and the rest run on to the next sheet, so no copy edit or window size can push text off
 * the paper.
 */
final class SheetFlow {
    private SheetFlow() {}

    /** Draws one line of a body with the top of its letters at {@code y}. */
    @FunctionalInterface
    interface Draw {
        void at(GuiGraphicsExtractor graphics, int y);
    }

    /**
     * One line of a body: how far below it the next line starts (with any paragraph gap after it), and how to draw it.
     */
    record Line(int pitch, Draw draw) {}

    /**
     * The index of the first line of each sheet, for lines that start at {@code top} on every sheet. A line fits while
     * its first {@code inkHeight} pixels end at or above {@code bottom}. Every sheet takes at least one line, so the
     * sheets always end; an empty body has one sheet.
     */
    static List<Integer> starts(List<Line> lines, int top, int bottom, int inkHeight) {
        List<Integer> starts = new ArrayList<>(List.of(0));
        int y = top;
        for (int index = 0; index < lines.size(); index++) {
            if (index > starts.getLast() && y + inkHeight > bottom) {
                starts.add(index);
                y = top;
            }
            y += lines.get(index).pitch();
        }
        return List.copyOf(starts);
    }

    /**
     * Draws the lines of sheet {@code sheet} (from 0) of {@code starts} from {@code top} down. A sheet past the last
     * draws nothing.
     */
    static void draw(GuiGraphicsExtractor graphics, List<Line> lines, List<Integer> starts, int sheet, int top) {
        if (sheet >= starts.size()) {
            return;
        }
        int end = sheet + 1 < starts.size() ? starts.get(sheet + 1) : lines.size();
        int y = top;
        for (Line line : lines.subList(Math.min(starts.get(sheet), lines.size()), Math.min(end, lines.size()))) {
            line.draw().at(graphics, y);
            y += line.pitch();
        }
    }
}
