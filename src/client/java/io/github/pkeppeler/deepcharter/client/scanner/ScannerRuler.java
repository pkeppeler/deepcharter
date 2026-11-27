package io.github.pkeppeler.deepcharter.client.scanner;

import io.github.pkeppeler.deepcharter.client.layer.Altimeter;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The depth ruler down the left edge of what shows of the scanner's map (#1272): a tick every
 * {@link ScannerLook#rulerTickBlocks()} blocks of depth, a longer one with the depth beside it every
 * {@link ScannerLook#rulerLabelBlocks()}, and a mark on the player's row. The pod stays centred, so the ruler
 * scrolls up past it as the pod digs, and a descent through uniform rock shows. A label is the altimeter's number for
 * its row ({@link Altimeter#readingNumber}), so the two always agree; the unit is left off to keep the label narrow
 * over the map.
 */
final class ScannerRuler {
    private static final int NONE = 0;
    private static final int TICK = 1;
    private static final int LABEL = 2;

    /** What each row carries, top row first, for the slice, cell size and look it was worked out for. */
    private static @Nullable Rows rows;

    private ScannerRuler() {}

    /** Drops the rows kept for the last slice, which hold its level, once the player has left the world. */
    static void release() {
        rows = null;
    }

    /** One row of the ruler: {@link #NONE}, {@link #TICK} or {@link #LABEL}, and a label's text (empty for none). */
    private record Row(int kind, String label) {}

    /**
     * What each row of a slice carries, top row first. It changes only with the slice, every few ticks, so a frame does
     * not work it out again.
     */
    private record Rows(ScanSlice slice, int cell, ScannerLook look, ClientLevel level, List<Row> each) {
        static Rows of(ScanSlice slice, int cell, ScannerLook look, ClientLevel level, Font font) {
            ScanArea area = slice.area();
            int feetY = slice.origin().getY();
            int labelEvery = labelBlocks(look, cell, font.lineHeight);
            List<Row> each = new ArrayList<>(area.rows());
            for (int row = 0; row < area.rows(); row++) {
                int y = feetY + area.up() - row;
                int depth = Depth.of(level, y);
                if (Math.floorMod(depth, labelEvery) == 0) {
                    each.add(new Row(LABEL, Altimeter.readingNumber(level, y)));
                } else {
                    each.add(new Row(Math.floorMod(depth, look.rulerTickBlocks()) == 0 ? TICK : NONE, ""));
                }
            }
            return new Rows(slice, cell, look, level, List.copyOf(each));
        }

        boolean isFor(ScanSlice other, int otherCell, ScannerLook otherLook, ClientLevel otherLevel) {
            return slice == other && cell == otherCell && look.equals(otherLook) && level == otherLevel;
        }
    }

    /**
     * Draws the ruler over the cells of {@code slice}, {@code cell} GUI pixels each, the top left cell at
     * ({@code left}, {@code top}). Only the part from {@code visibleLeft} across and from {@code visibleTop} to
     * {@code visibleBottom} shows (the cockpit's window cuts a slice larger than it), so the ruler sits at the left
     * edge of that part and a label that would cross its top or bottom is left out.
     */
    static void draw(
            GuiGraphicsExtractor graphics,
            Minecraft client,
            ScanSlice slice,
            ScannerLook look,
            int cell,
            int left,
            int top,
            int visibleLeft,
            int visibleTop,
            int visibleBottom) {
        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        // Both are null for a frame after a disconnect, before the tick clears the slice.
        if (level == null || player == null) {
            return;
        }
        Font font = client.font;
        Rows drawn = rows;
        if (drawn == null || !drawn.isFor(slice, cell, look, level)) {
            drawn = Rows.of(slice, cell, look, level, font);
            rows = drawn;
        }
        ScanArea area = slice.area();
        int edge = Math.max(left, visibleLeft);
        int shownTop = Math.max(top, visibleTop);
        int shownBottom = Math.min(top + area.rows() * cell, visibleBottom);
        for (int row = 0; row < area.rows(); row++) {
            int middle = top + row * cell + cell / 2;
            Row here = drawn.each().get(row);
            if (here.kind() == NONE || middle < shownTop || middle >= shownBottom) {
                continue;
            }
            boolean labelled = here.kind() == LABEL;
            int length = labelled ? look.rulerLabelTickPixels() : look.rulerTickPixels();
            graphics.fill(edge, middle, edge + length, middle + 1, look.rulerTickColor());
            if (labelled) {
                label(graphics, font, look, here.label(), edge, middle, shownTop, shownBottom);
            }
        }
        int playerUp = player.getBlockY() - slice.origin().getY();
        if (area.contains(0, playerUp)) {
            int rowTop = top + (area.up() - playerUp) * cell;
            int markTop = Math.max(rowTop, shownTop);
            int markBottom = Math.min(rowTop + cell, shownBottom);
            if (markTop < markBottom) {
                graphics.fill(edge, markTop, edge + look.rulerPodMarkPixels(), markBottom, look.rulerPodMarkColor());
            }
        }
    }

    /**
     * Blocks of depth between labels: {@link ScannerLook#rulerLabelBlocks()}, or the smallest multiple of it whose rows
     * span {@link ScannerLook#rulerLabelGapPixels()} and a line of text, so that small cells do not stack the labels
     * over the map.
     */
    private static int labelBlocks(ScannerLook look, int cell, int lineHeight) {
        int step = look.rulerLabelBlocks();
        int gap = Math.max(look.rulerLabelGapPixels(), lineHeight + 1);
        return step * Math.max(1, Math.ceilDiv(gap, step * cell));
    }

    /**
     * The label {@code text} centred on the row whose middle is {@code middle}, on its backing; left out where it would
     * cross {@code shownTop} or {@code shownBottom}. A digit fills the top {@code lineHeight - 2} rows of a line, so
     * that is what is centred.
     */
    private static void label(
            GuiGraphicsExtractor graphics,
            Font font,
            ScannerLook look,
            String text,
            int edge,
            int middle,
            int shownTop,
            int shownBottom) {
        int x = edge + look.rulerLabelTickPixels() + 1;
        int y = middle - (font.lineHeight - 2) / 2;
        if (y - 1 < shownTop || y + font.lineHeight > shownBottom) {
            return;
        }
        graphics.fill(x - 1, y - 1, x + font.width(text) + 1, y + font.lineHeight - 1, look.rulerLabelBackColor());
        graphics.text(font, text, x, y, look.rulerLabelColor(), false);
    }
}
