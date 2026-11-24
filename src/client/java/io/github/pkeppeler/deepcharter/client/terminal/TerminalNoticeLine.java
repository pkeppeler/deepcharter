package io.github.pkeppeler.deepcharter.client.terminal;

import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalNotices;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The lines a terminal's view carries under its header ({@code TerminalView.notices}, #993): a risk line of an outpost. A terminal screen
 * draws them where {@link #draw} puts them and moves what is under it down by {@link #height}. A line the server marked good ({@link TerminalNotices#good}) is drawn in the
 * screen's phosphor, any other in its amber, as a refusal is, so the colours are the theme's.
 */
public final class TerminalNoticeLine {
    /** Where the lines start, under the header's text, in the room the header's rule leaves. */
    private static final int BELOW_HEADER = 10;

    private TerminalNoticeLine() {}

    private record Line(String text, int color) {}

    private static List<Line> lines(Font font, TerminalView view, int width) {
        CrtTuning tuning = CrtTuning.current();
        List<Line> lines = new ArrayList<>();
        for (Component notice : view.notices()) {
            int color = TerminalNotices.isGood(notice) ? tuning.phosphorColor() : tuning.refusalColor();
            for (String wrapped : CrtText.wrap(font, notice.getString(), width)) {
                lines.add(new Line(wrapped, color));
            }
        }
        return lines;
    }

    /** How much room the lines take under the header, 0 for a view with none: what a screen adds to the y of everything below. */
    public static int height(Font font, TerminalView view, int width) {
        return lines(font, view, width).size()
                * (font.lineHeight + CrtTuning.current().lineSpacing());
    }

    /** Draws the lines of {@code view} under a header whose text starts at {@code headerTop}, {@code width} pixels wide from {@code x}. */
    public static void draw(
            GuiGraphicsExtractor graphics, Font font, TerminalView view, int x, int headerTop, int width) {
        int y = headerTop + font.lineHeight + BELOW_HEADER;
        for (Line line : lines(font, view, width)) {
            CrtDraw.glowText(graphics, font, line.text(), x, y, line.color());
            y += font.lineHeight + CrtTuning.current().lineSpacing();
        }
    }
}
