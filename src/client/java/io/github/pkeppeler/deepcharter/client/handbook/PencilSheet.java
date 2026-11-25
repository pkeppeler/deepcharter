package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.handbook.Notes;
import io.github.pkeppeler.deepcharter.handbook.PencilPath;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The pencil sheet: the paths of earlier miners, drawn in the green of a margin note. Each path is a row, a hand-drawn box for the
 * Note that draws it, a dashed pencil line, an x where the place is, and a box for what the miner pencilled there. A path whose
 * place the charter has reached is inked in: a solid line, the x circled, and the box naming the place. The sheet is a fixed page:
 * nothing drags or zooms, and every row fits the area it is given. This class lays out and draws; the screen owns the page. Every
 * colour and length is in {@link HandbookScreenTuning} and {@link QuestMapTuning}.
 */
final class PencilSheet {
    private PencilSheet() {}

    /** One path's row: its two boxes, the middle of its x, and where the line runs, all in screen pixels. */
    record Row(HandbookPage.PencilEntry entry, QuestMap.Rect source, QuestMap.Rect payoff, int markX, int markY) {}

    /** Where every row of a sheet sits. */
    record Layout(List<Row> rows) {}

    /**
     * Lays {@code entries} out in rows between {@code top} and {@code bottom}, from {@code left}, {@code width} across. Rows take
     * {@link QuestMapTuning#pencilRowPitch()} each, less when there is not that much room; a box is never shorter than one line of
     * text.
     */
    // const-param: the screen passes font.lineHeight, which javac folds to 9 (a constant final field in 26.3's Font);
    // the parameter keeps the layout tied to the font
    static Layout layout(
            List<HandbookPage.PencilEntry> entries, int left, int top, int width, int bottom, int lineHeight) {
        QuestMapTuning q = QuestMapTuning.current();
        int count = Math.max(1, entries.size());
        int pitch = Math.min(q.pencilRowPitch(), (bottom - top + q.pencilRowGap()) / count);
        int boxHeight = Math.max(lineHeight + q.pencilTextTop(), pitch - q.pencilRowGap());
        int sourceWidth = width * q.pencilSourcePercent() / 100;
        int markWidth = 2 * q.pencilMarkSize() + 1;
        int payoffWidth = width - sourceWidth - q.pencilArrowMin() - markWidth - q.pencilMarkGap();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            int y = top + i * pitch;
            int payoffLeft = left + width - payoffWidth;
            rows.add(new Row(
                    entries.get(i),
                    new QuestMap.Rect(left, y, sourceWidth, boxHeight),
                    new QuestMap.Rect(payoffLeft, y, payoffWidth, boxHeight),
                    payoffLeft - q.pencilMarkGap() - q.pencilMarkSize(),
                    y + boxHeight / 2));
        }
        return new Layout(List.copyOf(rows));
    }

    /** The label of the box of the Note that draws {@code path}: its number and title, as the Notes list shows it. */
    static Component sourceLabel(PencilPath path) {
        return Component.translatable(
                "deepcharter.handbook.notes.entry",
                String.format("N%02d", path.fromNote()),
                Component.translatable(Notes.titleKey(path.from())));
    }

    /** The rows of {@code layout}, drawn. */
    static void draw(GuiGraphicsExtractor graphics, Font font, Layout layout) {
        HandbookScreenTuning t = HandbookScreenTuning.current();
        QuestMapTuning q = QuestMapTuning.current();
        int pencil = Colors.opaque(t.marginInkColor());
        int seed = 0;
        for (Row row : layout.rows()) {
            PencilPath path = row.entry().path();
            boolean done = row.entry().done() || ClientPencil.isDone(path);
            seed++;
            box(graphics, font, row.source(), sourceLabel(path), pencil, seed);
            box(
                    graphics,
                    font,
                    row.payoff(),
                    Component.translatable(done ? path.placeKey() : path.hintKey()),
                    pencil,
                    seed + 50);
            int midY = row.markY();
            int tip = row.markX() - q.pencilMarkSize() - q.pencilTipGap();
            int start = row.source().right() + q.pencilStartGap();
            PaperDraw.arrow(
                    graphics,
                    new int[][] {
                        {start, midY},
                        {(start + tip) / 2, midY + PaperDraw.drift(seed, 20, q.pencilWobble())},
                        {tip, midY}
                    },
                    pencil,
                    q.arrowHeadSize(),
                    done ? 1 : q.dashOn(),
                    done ? 0 : q.dashOff());
            PaperDraw.cross(graphics, row.markX(), midY, q.pencilMarkSize(), pencil);
            if (done) {
                PaperDraw.ring(graphics, row.markX(), midY, q.pencilMarkSize() + q.pencilRingExtra(), pencil);
            }
        }
    }

    private static void box(
            GuiGraphicsExtractor graphics, Font font, QuestMap.Rect box, Component text, int color, int seed) {
        QuestMapTuning q = QuestMapTuning.current();
        PaperDraw.sketchBox(graphics, box.x(), box.y(), box.right(), box.bottom(), color, seed, q.pencilWobble());
        int lines = Math.max(1, (box.h() - q.pencilTextTop()) / (font.lineHeight + q.pencilLineGap()));
        int y = box.y() + q.pencilTextTop();
        for (String line : QuestMap.clip(font, text.getString(), box.w() - 2 * q.pencilBoxInset(), lines)) {
            graphics.text(
                    font, PaperDraw.ink(Component.literal(line), color), box.x() + q.pencilBoxInset(), y, color, false);
            y += font.lineHeight + q.pencilLineGap();
        }
    }
}
