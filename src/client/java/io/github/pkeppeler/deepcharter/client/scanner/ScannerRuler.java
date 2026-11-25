package io.github.pkeppeler.deepcharter.client.scanner;

import io.github.pkeppeler.deepcharter.client.layer.Altimeter;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/**
 * The depth ruler down the scanner's left edge (#1272): a tick every {@link ScannerLook#rulerTickBlocks()} blocks of
 * depth, a longer one with the depth beside it every {@link ScannerLook#rulerLabelBlocks()}, and a mark on the player's
 * row. The pod stays centred, so the ruler scrolls up past it as the pod digs, and a descent through uniform rock
 * shows. A label is the altimeter's number for its row ({@link Altimeter#feet}), so the two always agree; the unit is
 * left off to keep the label narrow over the map.
 */
final class ScannerRuler {
    private ScannerRuler() {}

    /**
     * Draws the ruler over the cells of {@code slice}, {@code cell} GUI pixels each, the top left
     * cell at ({@code left}, {@code top}).
     */
    static void draw(
            GuiGraphicsExtractor graphics,
            Minecraft client,
            ScanSlice slice,
            ScannerLook look,
            int cell,
            int left,
            int top) {
        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        // Both are null for a frame after a disconnect, before the tick clears the slice.
        if (level == null || player == null) {
            return;
        }
        ScanArea area = slice.area();
        int feetY = slice.origin().getY();
        Font font = client.font;
        int labelEvery = labelBlocks(look, cell, font.lineHeight);
        for (int up = area.up(); up >= -area.down(); up--) {
            int depth = Depth.of(level, feetY + up);
            int middle = rowTop(area, cell, top, up) + cell / 2;
            if (Math.floorMod(depth, labelEvery) == 0) {
                graphics.fill(left, middle, left + look.rulerLabelTickPixels(), middle + 1, look.rulerTickColor());
                label(
                        graphics,
                        font,
                        look,
                        Altimeter.feet(level, feetY + up),
                        left,
                        middle,
                        top,
                        top + area.rows() * cell);
            } else if (Math.floorMod(depth, look.rulerTickBlocks()) == 0) {
                graphics.fill(left, middle, left + look.rulerTickPixels(), middle + 1, look.rulerTickColor());
            }
        }
        int playerUp = player.getBlockY() - feetY;
        if (area.contains(0, playerUp)) {
            int rowTop = rowTop(area, cell, top, playerUp);
            graphics.fill(left, rowTop, left + look.rulerPodMarkPixels(), rowTop + cell, look.rulerPodMarkColor());
        }
    }

    private static int rowTop(ScanArea area, int cell, int top, int up) {
        return top + (area.up() - up) * cell;
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
     * cross the map's top or bottom edge. A digit fills the top {@code lineHeight - 2} rows of a line, so that is what
     * is centred.
     */
    private static void label(
            GuiGraphicsExtractor graphics,
            Font font,
            ScannerLook look,
            String text,
            int left,
            int middle,
            int mapTop,
            int mapBottom) {
        int x = left + look.rulerLabelTickPixels() + 1;
        int y = middle - (font.lineHeight - 2) / 2;
        if (y - 1 < mapTop || y + font.lineHeight > mapBottom) {
            return;
        }
        graphics.fill(x - 1, y - 1, x + font.width(text) + 1, y + font.lineHeight - 1, look.rulerLabelBackColor());
        graphics.text(font, text, x, y, look.rulerLabelColor(), false);
    }
}
