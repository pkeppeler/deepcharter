package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The quest map of one chapter: its directives as a flowchart of ink-outlined boxes on the paper, the next chapter's in faint ink,
 * anything further as black redaction bars, and the index card of the step the player picked. This class lays out and draws; the
 * screen owns the state. Layout is in map pixels from the flowchart's top left corner.
 * Every colour and length is in {@link HandbookScreenTuning} and {@link QuestMapTuning}.
 */
final class QuestMap {
    private QuestMap() {}

    /** A box in map pixels. */
    record Rect(int x, int y, int w, int h) {
        int right() {
            return x + w;
        }

        int bottom() {
            return y + h;
        }

        int centerX() {
            return x + w / 2;
        }

        int centerY() {
            return y + h / 2;
        }

        boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }
    }

    /**
     * What a chapter's map shows: the chapter, the chapter after it if the player may read its steps ({@code ahead}), and whether
     * redaction bars close the map, because a chapter after this one is still classified.
     */
    record Model(HandbookPage.Chapter main, HandbookPage.@Nullable Chapter ahead, boolean bars) {}

    /** Where everything of a {@link Model} sits, in map pixels. {@code aheadTitleY} is -1 when there is no next chapter to show. */
    record Layout(
            int columns,
            int boxWidth,
            List<Rect> main,
            List<Rect> ahead,
            int aheadTitleY,
            List<Rect> bars,
            int noteY,
            int width,
            int height) {
        /** The index of the main step under map point ({@code x}, {@code y}), or -1. */
        int stepAt(double x, double y) {
            for (int i = 0; i < main.size(); i++) {
                if (main.get(i).contains(x, y)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** The map of chapter {@code number}, which must have a chapter page. */
    static Model model(Map<Integer, HandbookPage.Chapter> chapters, int number) {
        HandbookPage.Chapter main = chapters.get(number);
        if (main == null) {
            throw new IllegalArgumentException("chapter " + number + " has no chapter page");
        }
        HandbookPage.Chapter next = chapters.get(number + 1);
        HandbookPage.Chapter after = chapters.get(number + 2);
        HandbookPage.Chapter ahead = next != null && next.visibility() != HandbookVisibility.CLASSIFIED ? next : null;
        boolean bars = (next != null && next.visibility() == HandbookVisibility.CLASSIFIED)
                || (after != null && after.visibility() == HandbookVisibility.CLASSIFIED);
        return new Model(main, ahead, bars);
    }

    /** The first directive of {@code chapter} not yet done, which the player circles: the step they are on. Null when all are done. */
    static HandbookChapter.@Nullable Entry current(HandbookPage.Chapter chapter) {
        return chapter.chapter().directives().stream()
                .filter(entry -> !chapter.completed().contains(entry.id()))
                .findFirst()
                .orElse(null);
    }

    /** The step whose card shows: the one the player picked, else the current step, else the first. */
    static HandbookChapter.Entry shown(HandbookPage.Chapter chapter, @Nullable Identifier picked) {
        List<HandbookChapter.Entry> steps = chapter.chapter().directives();
        if (picked != null) {
            for (HandbookChapter.Entry entry : steps) {
                if (entry.id().equals(picked)) {
                    return entry;
                }
            }
        }
        HandbookChapter.Entry current = current(chapter);
        return current != null ? current : steps.getFirst();
    }

    /** How much of the next chapter a map shows, from all of it to none; each is tried only if the one before is too tall. */
    private enum Ahead {
        ALL,
        ONE_ROW,
        TITLE
    }

    /**
     * Lays {@code model} out in a map {@code mapWidth} wide, with {@code noteLines} lines of margin note, in at most
     * {@code availableHeight} pixels when that can be done. Steps run in rows of up to {@link QuestMapTuning#stepColumns()}, fewer
     * on a narrow sheet. A map that is too tall tries, in order: the note beside the last row of steps when that row is partial
     * (the empty columns beside it hold the note), then the next chapter cut to its first row of steps, then to its title alone,
     * each both with the note below and beside. The next chapter's title, the redaction bars with their stamp and the note always
     * stay. Nothing is scaled.
     */
    static Layout layout(int mapWidth, int availableHeight, Model model, int lineHeight, int noteLines) {
        Layout smallest = null;
        for (Ahead shown : Ahead.values()) {
            for (boolean beside : new boolean[] {false, true}) {
                Layout layout = layout(mapWidth, model, lineHeight, noteLines, shown, beside);
                if (layout == null) {
                    continue;
                }
                if (layout.height() <= availableHeight) {
                    return layout;
                }
                if (smallest == null || layout.height() <= smallest.height()) {
                    smallest = layout;
                }
            }
            if (model.ahead() == null) {
                break;
            }
        }
        return Objects.requireNonNull(smallest, "the plain layout always exists");
    }

    /** The layout for one rung, or null when {@code beside} asks for a note beside a last row that has no room for it. */
    private static @Nullable Layout layout(
            int mapWidth, Model model, int lineHeight, int noteLines, Ahead shown, boolean beside) {
        QuestMapTuning q = QuestMapTuning.current();
        int columns =
                Math.max(1, Math.min(q.stepColumns(), (mapWidth + q.stepGap()) / (q.stepMinWidth() + q.stepGap())));
        int boxWidth = (mapWidth - q.stepGap() * (columns - 1)) / columns;
        List<Rect> main = grid(model.main().chapter().directives().size(), 0, columns, boxWidth);
        int bottom = main.getLast().bottom();
        int noteY = 0;
        if (beside) {
            int lastRow = main.size() % columns;
            int freeLeft = lastRow == 0 ? mapWidth : lastRow * (boxWidth + q.stepGap());
            if (noteLines == 0 || lastRow == 0 || freeLeft + q.noteWidth() > mapWidth - q.noteInset()) {
                return null;
            }
            noteY = main.getLast().y();
            bottom = Math.max(bottom, noteY + noteLines * (lineHeight + 1));
        }
        int height = bottom;
        List<Rect> ahead = List.of();
        int aheadTitleY = -1;
        if (model.ahead() != null) {
            aheadTitleY = bottom + q.aheadGap();
            bottom = aheadTitleY + lineHeight;
            if (shown != Ahead.TITLE) {
                int steps = model.ahead().chapter().directives().size();
                ahead = grid(
                        shown == Ahead.ONE_ROW ? Math.min(steps, columns) : steps,
                        aheadTitleY + q.aheadLabelGap(),
                        columns,
                        boxWidth);
                bottom = ahead.getLast().bottom();
            }
            height = bottom;
        }
        List<Rect> bars = new ArrayList<>();
        if (model.bars()) {
            int barsY = bottom + q.aheadGap();
            int[] percents = {q.redactionBarPercent1(), q.redactionBarPercent2(), q.redactionBarPercent3()};
            for (int i = 0; i < Math.min(columns, percents.length); i++) {
                bars.add(new Rect(i * (boxWidth + q.stepGap()), barsY, boxWidth * percents[i] / 100, lineHeight));
            }
            if (!beside) {
                noteY = barsY + lineHeight + q.barsNoteGap();
            }
            height = Math.max(barsY + lineHeight, barsY + lineHeight / 2 + q.stampYOffset() + q.stampReach());
        } else if (!beside) {
            noteY = bottom + q.aheadGap();
        }
        if (noteLines > 0) {
            height = Math.max(height, noteY + noteLines * (lineHeight + 1));
        }
        return new Layout(columns, boxWidth, main, ahead, aheadTitleY, List.copyOf(bars), noteY, mapWidth, height);
    }

    private static List<Rect> grid(int count, int top, int columns, int boxWidth) {
        QuestMapTuning q = QuestMapTuning.current();
        List<Rect> boxes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boxes.add(new Rect(
                    i % columns * (boxWidth + q.stepGap()),
                    top + i / columns * q.stepPitch(),
                    boxWidth,
                    q.stepHeight()));
        }
        return List.copyOf(boxes);
    }

    /**
     * Draws the flowchart with its origin at ({@code originX}, {@code originY}), clipped to {@code clip}, in screen pixels.
     */
    static void drawChart(
            GuiGraphicsExtractor graphics,
            Font font,
            Model model,
            Layout layout,
            int originX,
            int originY,
            Rect clip,
            @Nullable Component note,
            Component classified,
            Component aheadTitle) {
        HandbookScreenTuning t = HandbookScreenTuning.current();
        QuestMapTuning q = QuestMapTuning.current();
        graphics.enableScissor(clip.x(), clip.y(), clip.right(), clip.bottom());
        graphics.pose().pushMatrix();
        graphics.pose().translate(originX, originY);
        boolean full = model.main().visibility() == HandbookVisibility.FULL;
        int ink = full ? t.inkColor() : t.faintInkColor();
        List<HandbookChapter.Entry> steps = model.main().chapter().directives();
        for (int i = 0; i < steps.size(); i++) {
            HandbookChapter.Entry step = steps.get(i);
            String text = full
                    ? Component.translatable(
                                    model.main().completed().contains(step.id())
                                            ? "deepcharter.handbook.chapter.directive.done"
                                            : "deepcharter.handbook.chapter.directive.open",
                                    step.text())
                            .getString()
                    : step.text().getString();
            stepBox(graphics, font, layout.main().get(i), text, ink);
        }
        connect(graphics, layout.main(), layout.columns(), ink);
        HandbookChapter.Entry current = full ? current(model.main()) : null;
        if (current != null) {
            Rect box = layout.main().get(steps.indexOf(current));
            PaperDraw.loop(
                    graphics,
                    box.x() - q.loopPadLeft(),
                    box.y() - q.loopPadTop(),
                    box.right() + q.loopPadRight(),
                    box.bottom() + q.loopPadBottom(),
                    q.loopRadius(),
                    Colors.opaque(t.inkColor()));
        }
        HandbookPage.Chapter ahead = model.ahead();
        if (ahead != null && layout.aheadTitleY() >= 0) {
            graphics.text(
                    font,
                    PaperDraw.ink(aheadTitle, t.faintInkColor()),
                    0,
                    layout.aheadTitleY(),
                    Colors.opaque(t.faintInkColor()),
                    false);
            List<HandbookChapter.Entry> aheadSteps = ahead.chapter().directives();
            for (int i = 0; i < layout.ahead().size(); i++) {
                stepBox(
                        graphics,
                        font,
                        layout.ahead().get(i),
                        aheadSteps.get(i).text().getString(),
                        t.faintInkColor());
            }
            connect(graphics, layout.ahead(), layout.columns(), t.faintInkColor());
            // at the right edge, past the end of the title it crosses, and not through a note set beside the last row
            if (!layout.ahead().isEmpty() && layout.noteY() > layout.aheadTitleY()) {
                Rect last = layout.main().getLast();
                int x = layout.width() - q.chapterLinkInset();
                PaperDraw.arrow(
                        graphics,
                        new int[][] {
                            {x, last.bottom() + q.chapterLinkTop()},
                            {x, layout.ahead().getFirst().y() - 1}
                        },
                        Colors.opaque(t.faintInkColor()),
                        q.arrowHeadSize(),
                        q.dashOn(),
                        q.dashOff());
            }
        }
        drawBars(graphics, font, layout, classified);
        if (note != null) {
            int right = layout.width() - q.noteInset();
            int y = layout.noteY();
            for (var line : font.split(PaperDraw.ink(note, t.marginInkColor()), q.noteWidth())) {
                graphics.text(font, line, right - font.width(line), y, Colors.opaque(t.marginInkColor()), false);
                y += font.lineHeight + 1;
            }
        }
        graphics.pose().popMatrix();
        graphics.disableScissor();
    }

    private static void drawBars(GuiGraphicsExtractor graphics, Font font, Layout layout, Component classified) {
        if (layout.bars().isEmpty()) {
            return;
        }
        HandbookScreenTuning t = HandbookScreenTuning.current();
        QuestMapTuning q = QuestMapTuning.current();
        List<Rect> bars = layout.bars();
        for (Rect bar : bars) {
            graphics.fill(bar.x(), bar.y(), bar.right(), bar.bottom(), t.redactionColor());
        }
        for (int i = 1; i < bars.size(); i++) {
            Rect a = bars.get(i - 1);
            Rect b = bars.get(i);
            graphics.fill(a.right(), a.centerY(), b.x(), a.centerY() + 1, t.redactionColor());
        }
        // a title between the steps and the bars would be crossed by the link, so with only a title it is left out
        if (layout.aheadTitleY() < 0 || !layout.ahead().isEmpty()) {
            Rect last = layout.ahead().isEmpty()
                    ? layout.main().getLast()
                    : layout.ahead().getLast();
            int dropX = last.x() + layout.boxWidth() * q.dropLinkPercent() / 100;
            graphics.fill(dropX, last.bottom(), dropX + 1, bars.getFirst().y(), t.redactionColor());
        }
        PaperDraw.stamp(
                graphics,
                font,
                classified,
                bars.getFirst().x() + layout.boxWidth() * q.stampXPercent() / 100,
                bars.getFirst().centerY() + q.stampYOffset(),
                q.classifiedStampDegrees(),
                t.stampColor());
    }

    /** A step: a one-pixel box in {@code color} with its text, cut with dots after {@link QuestMapTuning#stepMaxLines()} lines. */
    private static void stepBox(GuiGraphicsExtractor graphics, Font font, Rect box, String text, int color) {
        QuestMapTuning q = QuestMapTuning.current();
        PaperDraw.border(graphics, box.x(), box.y(), box.right(), box.bottom(), Colors.opaque(color));
        int y = box.y() + 2;
        for (String line : clip(font, text, box.w() - 2 * q.stepTextInset(), q.stepMaxLines())) {
            graphics.text(
                    font,
                    PaperDraw.ink(Component.literal(line), color),
                    box.x() + q.stepTextInset(),
                    y,
                    Colors.opaque(color),
                    false);
            y += font.lineHeight + 1;
        }
    }

    /** {@code text} wrapped to {@code width}, in at most {@code maxLines} lines, the last one ending in dots when text was cut. */
    static List<String> clip(Font font, String text, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        for (FormattedText line : font.getSplitter().splitLines(Component.literal(text), width, Style.EMPTY)) {
            lines.add(line.getString());
        }
        if (lines.size() <= maxLines) {
            return lines;
        }
        String rest = String.join(" ", lines.subList(maxLines - 1, lines.size()));
        List<String> clipped = new ArrayList<>(lines.subList(0, maxLines - 1));
        clipped.add(font.plainSubstrByWidth(rest, width - font.width("...")).stripTrailing() + "...");
        return clipped;
    }

    /**
     * Arrows between the steps: along each row, and from the last box of a row down to the last box of the next row, with an elbow
     * when the next row is short.
     */
    private static void connect(GuiGraphicsExtractor graphics, List<Rect> boxes, int columns, int color) {
        QuestMapTuning q = QuestMapTuning.current();
        int ink = Colors.opaque(color);
        for (int i = 0; i + 1 < boxes.size(); i++) {
            Rect from = boxes.get(i);
            Rect to = boxes.get(i + 1);
            if (from.y() == to.y()) {
                PaperDraw.arrow(
                        graphics,
                        new int[][] {{from.right(), from.centerY()}, {to.x() - 1, from.centerY()}},
                        ink,
                        q.arrowHeadSize(),
                        0,
                        0);
            }
        }
        for (int rowEnd = columns - 1; rowEnd + 1 < boxes.size(); rowEnd += columns) {
            Rect from = boxes.get(rowEnd);
            Rect to = boxes.get(Math.min(rowEnd + columns, boxes.size() - 1));
            int mid = (from.bottom() + to.y()) / 2;
            int[][] path = from.centerX() == to.centerX()
                    ? new int[][] {{from.centerX(), from.bottom()}, {to.centerX(), to.y() - 1}}
                    : new int[][] {
                        {from.centerX(), from.bottom()},
                        {from.centerX(), mid},
                        {to.centerX(), mid},
                        {to.centerX(), to.y() - 1}
                    };
            PaperDraw.arrow(graphics, path, ink, q.arrowHeadSize(), 0, 0);
        }
    }

    /** One needed item on the card: where its icon sits, what is needed and what the player holds. */
    record CardItem(Item item, Rect box, int have, int need) {}

    /** The index card of a step, in screen pixels: its box, the link to the chapter's page, and each needed item. */
    record Card(Rect box, int stepNumber, Rect link, List<CardItem> items) {
        /** The item under screen point ({@code x}, {@code y}), or null. */
        @Nullable
        CardItem itemAt(double x, double y) {
            for (CardItem item : items) {
                if (item.box().contains(x, y)) {
                    return item;
                }
            }
            return null;
        }
    }

    /**
     * Lays out the card of {@code step}, the {@code number}th of its chapter, hanging off the top right corner of a sheet at
     * ({@code paperRight}, {@code paperTop}). {@code have} counts what the player holds of an item; a step that is {@code done}
     * needs nothing more, since its items are spent doing it.
     */
    static Card card(
            Font font,
            HandbookChapter.Entry step,
            int number,
            int paperRight,
            int paperTop,
            String link,
            boolean done,
            java.util.function.ToIntFunction<Item> have) {
        QuestMapTuning q = QuestMapTuning.current();
        int w = q.cardWidth();
        int x = paperRight - q.cardRightInset() - w;
        int y = Math.max(q.cardMinY(), paperTop + q.cardTop());
        int textWidth = w - 2 * q.cardInset() - 2;
        int lines = font.split(PaperDraw.ink(step.text(), 0), textWidth).size();
        List<CardItem> items = new ArrayList<>();
        int perRow = Math.max(1, (w - q.cardItemInset() + q.cardItemPitch() / 2) / q.cardItemPitch());
        int itemsTop = y + q.cardTextTop() + lines * (font.lineHeight + 1) + q.cardItemsGap();
        for (HandbookChapter.Need need : step.needs()) {
            Item item = BuiltInRegistries.ITEM.getOptional(need.item()).orElse(null);
            if (item == null) {
                continue;
            }
            int index = items.size();
            items.add(new CardItem(
                    item,
                    new Rect(
                            x + q.cardItemInset() + index % perRow * q.cardItemPitch(),
                            itemsTop + index / perRow * q.cardItemRow(),
                            q.itemIconSize(),
                            q.itemIconSize()),
                    done ? need.count() : have.applyAsInt(item),
                    need.count()));
        }
        int rows = (items.size() + perRow - 1) / perRow;
        int h = q.cardTextTop() + lines * (font.lineHeight + 1) + rows * q.cardItemRow() + q.cardBottomPad();
        int linkWidth = font.width(PaperDraw.ink(Component.literal(link), 0));
        Rect linkBox =
                new Rect(x + w - q.cardInset() - 2 - linkWidth, y + q.cardHeadTop(), linkWidth, font.lineHeight + 2);
        return new Card(new Rect(x, y, w, h), number, linkBox, List.copyOf(items));
    }

    static void drawCard(
            GuiGraphicsExtractor graphics,
            Font font,
            Card card,
            HandbookChapter.Entry step,
            Component stepLabel,
            String link) {
        HandbookScreenTuning t = HandbookScreenTuning.current();
        QuestMapTuning q = QuestMapTuning.current();
        Rect box = card.box();
        int shadow = t.shadowOffset();
        graphics.fill(box.x() + shadow, box.y() + shadow, box.right() + shadow, box.bottom() + shadow, t.shadowColor());
        graphics.fill(box.x(), box.y(), box.right(), box.bottom(), t.paperColor());
        PaperDraw.ruledLines(
                graphics, box.x(), box.right(), box.y() + q.cardRuleTop(), box.bottom() - q.cardRuleBottom());
        PaperDraw.border(graphics, box.x(), box.y(), box.right(), box.bottom(), Colors.opaque(t.paperEdgeColor()));
        graphics.text(
                font,
                PaperDraw.ink(stepLabel, t.faintInkColor()),
                box.x() + q.cardInset(),
                box.y() + q.cardHeadTop(),
                Colors.opaque(t.faintInkColor()),
                false);
        Rect linkBox = card.link();
        graphics.text(
                font,
                PaperDraw.ink(Component.literal(link), t.inkColor()),
                linkBox.x(),
                linkBox.y(),
                Colors.opaque(t.inkColor()),
                false);
        graphics.fill(
                linkBox.x(),
                linkBox.y() + font.lineHeight,
                linkBox.right(),
                linkBox.y() + font.lineHeight + 1,
                t.inkColor());
        graphics.fill(
                box.x() + 1,
                box.y() + q.cardHeadRule(),
                box.right() - 1,
                box.y() + q.cardHeadRule() + 1,
                t.stampColor());
        PaperDraw.wrapped(
                graphics,
                font,
                step.text(),
                box.x() + q.cardInset(),
                box.y() + q.cardTextTop(),
                box.w() - 2 * q.cardInset() - 2,
                t.inkColor());
        for (CardItem item : card.items()) {
            Rect icon = item.box();
            graphics.fakeItem(new ItemStack(item.item()), icon.x(), icon.y());
            boolean met = item.have() >= item.need();
            int color = met ? t.faintInkColor() : t.inkColor();
            Component count = PaperDraw.ink(Component.literal(item.have() + "/" + item.need()), color);
            int textX = icon.right() + q.itemCountGap();
            graphics.text(font, count, textX, icon.y() + q.itemCountTop(), Colors.opaque(color), false);
            if (met) {
                int strikeY = icon.y() + q.itemCountTop() + font.lineHeight / 2 - q.strikeLift();
                graphics.fill(icon.right() + 1, strikeY, textX + font.width(count), strikeY + 1, Colors.opaque(color));
            }
        }
    }
}
