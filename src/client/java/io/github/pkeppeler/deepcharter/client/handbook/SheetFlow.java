package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Lines of the handbook laid out down the paper, and the body of a page that flows (a chapter's text, a page of Appendix A, a
 * Note) split into sheets: each sheet takes the lines that fit above Back and Next, and the rest run on to the next sheet, so
 * no copy edit or window size can push text off the paper.
 */
final class SheetFlow {
    private SheetFlow() {}

    /** Draws one line with the top of its letters at {@code y}. */
    @FunctionalInterface
    interface Draw {
        void at(GuiGraphicsExtractor graphics, int y);
    }

    /** One line: how far below it the next line starts (with any gap after it), and how to draw it. */
    record Line(int pitch, Draw draw) {}

    /** How far down {@code lines} reach, gaps included. */
    static int height(List<Line> lines) {
        return lines.stream().mapToInt(Line::pitch).sum();
    }

    /** Draws {@code lines} from {@code top} down and returns the y below them. */
    static int draw(GuiGraphicsExtractor graphics, List<Line> lines, int top) {
        int y = top;
        for (Line line : lines) {
            line.draw().at(graphics, y);
            y += line.pitch();
        }
        return y;
    }

    /**
     * The index of the first line of each sheet, for lines that start at {@code firstTop} on the first sheet and at {@code laterTop}
     * on the others. A line fits while its first {@code inkHeight} pixels end at or above {@code bottom}. Every sheet takes at least
     * one line, so the sheets always end; an empty body has one sheet.
     */
    static List<Integer> starts(List<Line> lines, int firstTop, int laterTop, int bottom, int inkHeight) {
        List<Integer> starts = new ArrayList<>(List.of(0));
        int y = firstTop;
        for (int index = 0; index < lines.size(); index++) {
            if (index > starts.getLast() && y + inkHeight > bottom) {
                starts.add(index);
                y = laterTop;
            }
            y += lines.get(index).pitch();
        }
        return List.copyOf(starts);
    }

    /** The lines of sheet {@code sheet} (from 0) of {@code starts}. */
    static List<Line> sheet(List<Line> lines, List<Integer> starts, int sheet) {
        int end = sheet + 1 < starts.size() ? starts.get(sheet + 1) : lines.size();
        return lines.subList(starts.get(sheet), end);
    }
}
