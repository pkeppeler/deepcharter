package io.github.pkeppeler.deepcharter.client.handbook;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.pkeppeler.deepcharter.client.theme.Colors;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The handbook's one mark of a thing a click opens (#1413): a chevron in faint ink inside the thing's right edge, and under the
 * mouse a highlight behind it, the chevron in full ink and the pointing hand. The quest map's steps carry it, and anything else on
 * the paper that a click opens, such as an item on a step's card (#1425), draws it the same way. Its sizes are {@link
 * QuestMapTuning#clickCueSize()} and {@link QuestMapTuning#clickCueInset()}; its colours are the paper's hover fill, faint ink
 * and ink in {@link HandbookScreenTuning}.
 */
final class ClickCue {
    private ClickCue() {}

    /** How much of a marked box's width, from its right edge in, the chevron takes: what the box's own text leaves free. */
    static int width() {
        QuestMapTuning q = QuestMapTuning.current();
        return q.clickCueInset() + q.clickCueSize() + 1;
    }

    /**
     * Marks the box from ({@code left}, {@code top}) to ({@code right}, {@code bottom}), exclusive, as one a click opens. Draw it
     * before the box's own content, because the highlight goes behind that.
     */
    static void draw(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, boolean hovered) {
        HandbookScreenTuning t = HandbookScreenTuning.current();
        QuestMapTuning q = QuestMapTuning.current();
        if (hovered) {
            graphics.fill(left, top, right, bottom, t.hoverFillColor());
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
        int size = q.clickCueSize();
        int tipX = right - 1 - q.clickCueInset();
        int midY = (top + bottom - 1) / 2;
        int ink = Colors.opaque(hovered ? t.inkColor() : t.faintInkColor());
        PaperDraw.line(graphics, tipX - size, midY - size, tipX, midY, ink, 1, 0);
        PaperDraw.line(graphics, tipX - size, midY + size, tipX, midY, ink, 1, 0);
    }
}
