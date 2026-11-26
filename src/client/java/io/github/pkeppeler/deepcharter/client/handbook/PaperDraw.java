package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.client.theme.Colors;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Drawing helpers of the paper look. Every colour comes from {@link HandbookScreenTuning}. */
final class PaperDraw {
    private PaperDraw() {}

    /** A sheet of paper with a drop shadow, faint ruled lines and a binding band down its left edge. */
    static void sheet(GuiGraphicsExtractor graphics, int left, int top, int width, int height) {
        HandbookScreenTuning t = HandbookScreenTuning.current();
        int shadow = t.shadowOffset();
        graphics.fill(left + shadow, top + shadow, left + width + shadow, top + height + shadow, t.shadowColor());
        graphics.fill(left, top, left + width, top + height, t.paperColor());
        int rule = Colors.withAlpha(t.paperEdgeColor(), t.ruleAlpha());
        for (int y = top + t.ruleSpacing() * 2; y < top + height - t.ruleSpacing(); y += t.ruleSpacing()) {
            graphics.fill(left + t.bindingWidth(), y, left + width - 1, y + 1, rule);
        }
        graphics.fill(left, top, left + t.bindingWidth(), top + height, t.bindingColor());
        border(graphics, left, top, left + width, top + height, t.paperEdgeColor());
    }

    /** A one-pixel rectangle outline. */
    static void border(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int color) {
        graphics.fill(left, top, right, top + 1, color);
        graphics.fill(left, bottom - 1, right, bottom, color);
        graphics.fill(left, top, left + 1, bottom, color);
        graphics.fill(right - 1, top, right, bottom, color);
    }

    /** {@code text} in italic ink of {@code color} (RGB; alpha is ignored): the vanilla italic font, coloured. */
    static Component ink(Component text, int color) {
        return text.copy().withStyle(style -> style.withItalic(true).withColor(Colors.rgb(color)));
    }

    /** Draws {@code text} wrapped to {@code width}. Returns the y below the last line. */
    static int wrapped(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color) {
        int lineY = y;
        for (FormattedCharSequence line : font.split(ink(text, color), width)) {
            graphics.text(font, line, x, lineY, Colors.opaque(color), false);
            lineY += font.lineHeight + 1;
        }
        return lineY;
    }

    /** Draws {@code text} centred on {@code centerX}, enlarged {@code scale} times. Returns the y below it. */
    static int centered(
            GuiGraphicsExtractor graphics, Font font, Component text, int centerX, int y, float scale, int color) {
        Component styled = ink(text, color);
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, y);
        graphics.pose().scale(scale, scale);
        graphics.text(font, styled, -font.width(styled) / 2, 0, Colors.opaque(color), false);
        graphics.pose().popMatrix();
        return y + Math.round(font.lineHeight * scale) + 2;
    }

    /** A rubber stamp: bold capitals in a double frame, turned by {@code degrees}. */
    static void stamp(
            GuiGraphicsExtractor graphics,
            Font font,
            Component text,
            int centerX,
            int centerY,
            float degrees,
            int color) {
        Component styled = text.copy().withStyle(Style.EMPTY.withBold(true).withColor(Colors.rgb(color)));
        int halfWidth = font.width(styled) / 2 + 5;
        int halfHeight = font.lineHeight / 2 + 3;
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, centerY);
        graphics.pose().rotate((float) Math.toRadians(degrees));
        border(graphics, -halfWidth, -halfHeight, halfWidth, halfHeight, Colors.opaque(color));
        border(graphics, -halfWidth + 2, -halfHeight + 2, halfWidth - 2, halfHeight - 2, Colors.opaque(color));
        graphics.text(font, styled, -font.width(styled) / 2, -font.lineHeight / 2 + 1, Colors.opaque(color), false);
        graphics.pose().popMatrix();
    }

    /** A solid black bar, like a word crossed out of a document before it is released. */
    static void redaction(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(
                x, y, x + width, y + height, HandbookScreenTuning.current().redactionColor());
    }

    /**
     * Draws {@code text} wrapped to {@code width}, in ink, with a black bar over each part between a pair of {@code ||} marks. The
     * marks themselves are not drawn. A redacted part is always drawn as bars, never as text. Returns the y below the last line.
     */
    static int redacted(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int width, int color) {
        List<List<Piece>> lines = redactedLines(font, text, width, color);
        int lineY = y;
        for (List<Piece> line : lines) {
            redactedLine(graphics, font, line, x, lineY, color);
            lineY += redactedPitch(font);
        }
        return y + Math.max(1, lines.size()) * redactedPitch(font);
    }

    /**
     * How far down one line of {@link #redacted} text starts below the last: the bars stand a pixel above and below the
     * letters.
     */
    static int redactedPitch(Font font) {
        return font.lineHeight + 3;
    }

    /** A word in ink, or a bar over a redacted run, {@code x} pixels from the left of its line. A bar has no word. */
    record Piece(Component word, int x, int width, boolean bar) {}

    /** The lines {@link #redacted} draws {@code text} in, wrapped to {@code width}, without drawing them. */
    static List<List<Piece>> redactedLines(Font font, String text, int width, int color) {
        int spaceWidth = font.width(" ");
        List<List<Piece>> lines = new ArrayList<>();
        List<Piece> line = new ArrayList<>();
        int cursor = 0;
        boolean previousRedacted = false;
        for (RedactionText.Token token : RedactionText.parse(text)) {
            Component word = ink(Component.literal(token.text()), color);
            int wordWidth = font.width(word);
            int gap = token.spaceBefore() ? spaceWidth : 0;
            if (cursor > 0 && cursor + gap + wordWidth > width) {
                lines.add(line);
                line = new ArrayList<>();
                cursor = 0;
                gap = 0;
                previousRedacted = false;
            }
            if (token.redacted()) {
                int barStart = previousRedacted ? cursor : cursor + gap;
                line.add(new Piece(Component.empty(), barStart, cursor + gap + wordWidth - barStart, true));
            } else {
                line.add(new Piece(word, cursor + gap, wordWidth, false));
            }
            cursor += gap + wordWidth;
            previousRedacted = token.redacted();
        }
        if (!line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }

    /** Draws one line of {@link #redactedLines} with its left edge at {@code x} and its letters' top at {@code y}. */
    static void redactedLine(GuiGraphicsExtractor graphics, Font font, List<Piece> line, int x, int y, int color) {
        for (Piece piece : line) {
            if (piece.bar()) {
                redaction(graphics, x + piece.x(), y - 1, piece.width(), font.lineHeight + 1);
            } else {
                graphics.text(font, piece.word(), x + piece.x(), y, Colors.opaque(color), false);
            }
        }
    }

    /** The faint ruled lines of a slip of paper spanning {@code left} to {@code right}: one every rule spacing from {@code top} to short of {@code bottom}. */
    static void ruledLines(GuiGraphicsExtractor graphics, int left, int right, int top, int bottom) {
        HandbookScreenTuning t = HandbookScreenTuning.current();
        int rule = Colors.withAlpha(t.paperEdgeColor(), t.ruleAlpha());
        for (int y = top; y < bottom; y += t.ruleSpacing()) {
            graphics.fill(left + 1, y, right - 1, y + 1, rule);
        }
    }

    /** One pixel. */
    private static void pixel(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x, y, x + 1, y + 1, color);
    }

    /** A one-pixel line (Bresenham). {@code dashOn} pixels drawn, then {@code dashOff} skipped; {@code dashOff} 0 is solid. */
    static void line(
            GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color, int dashOn, int dashOff) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        for (int step = 0; ; step++) {
            if (dashOff == 0 || step % (dashOn + dashOff) < dashOn) {
                pixel(graphics, x, y, color);
            }
            if (x == x1 && y == y1) {
                return;
            }
            int doubled = 2 * err;
            if (doubled >= dy) {
                err += dy;
                x += sx;
            }
            if (doubled <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    /**
     * A box drawn by hand in one colour: each side a line of its own, a pixel or so off true and running past the corner, so no two
     * boxes with different {@code seed}s match. {@code wobble} is the most a side drifts or overshoots; 0 draws a true box.
     */
    static void sketchBox(
            GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int color, int seed, int wobble) {
        line(
                graphics,
                left - Math.abs(drift(seed, 0, wobble)),
                top + drift(seed, 1, wobble),
                right + Math.abs(drift(seed, 2, wobble)),
                top + drift(seed, 3, wobble),
                color,
                1,
                0);
        line(
                graphics,
                left - Math.abs(drift(seed, 4, wobble)),
                bottom + drift(seed, 5, wobble),
                right + Math.abs(drift(seed, 6, wobble)),
                bottom + drift(seed, 7, wobble),
                color,
                1,
                0);
        line(
                graphics,
                left + drift(seed, 8, wobble),
                top - Math.abs(drift(seed, 9, wobble)),
                left + drift(seed, 10, wobble),
                bottom + Math.abs(drift(seed, 11, wobble)),
                color,
                1,
                0);
        line(
                graphics,
                right + drift(seed, 12, wobble),
                top - Math.abs(drift(seed, 13, wobble)),
                right + drift(seed, 14, wobble),
                bottom + Math.abs(drift(seed, 15, wobble)),
                color,
                1,
                0);
    }

    /** A steady pseudo-random whole number from {@code -wobble} to {@code wobble}: the same for the same {@code seed} and {@code index}. */
    static int drift(int seed, int index, int wobble) {
        if (wobble == 0) {
            return 0;
        }
        return new Random(seed * 1_000_003L + index * 7_919L).nextInt(2 * wobble + 1) - wobble;
    }

    /** A ring of radius {@code radius} round ({@code x}, {@code y}): the circle that marks a spot as done. */
    static void ring(GuiGraphicsExtractor graphics, int x, int y, int radius, int color) {
        for (int degrees = 0; degrees < 360; degrees++) {
            double angle = Math.toRadians(degrees);
            pixel(
                    graphics,
                    x + (int) Math.round(Math.cos(angle) * radius),
                    y + (int) Math.round(Math.sin(angle) * radius),
                    color);
        }
    }

    /** A cross of two strokes through ({@code x}, {@code y}), its arms {@code reach} pixels out: the x that marks a spot. */
    static void cross(GuiGraphicsExtractor graphics, int x, int y, int reach, int color) {
        line(graphics, x - reach, y - reach, x + reach, y + reach, color, 1, 0);
        line(graphics, x - reach, y + reach, x + reach, y - reach, color, 1, 0);
    }

    /** A line through {@code points} (x, y pairs), with a head of barbs {@code head} long on its last end if {@code head} is above 0. */
    static void arrow(GuiGraphicsExtractor graphics, int[][] points, int color, int head, int dashOn, int dashOff) {
        for (int i = 1; i < points.length; i++) {
            line(graphics, points[i - 1][0], points[i - 1][1], points[i][0], points[i][1], color, dashOn, dashOff);
        }
        if (head <= 0) {
            return;
        }
        int[] from = points[points.length - 2];
        int[] to = points[points.length - 1];
        int ux = Integer.signum(to[0] - from[0]);
        int uy = Integer.signum(to[1] - from[1]);
        for (int back = 1; back <= head; back++) {
            // the barbs lie either side of the shaft, one pixel further out for each pixel further back
            int px = -uy;
            int py = ux;
            pixel(graphics, to[0] - ux * back + px * back, to[1] - uy * back + py * back, color);
            pixel(graphics, to[0] - ux * back - px * back, to[1] - uy * back - py * back, color);
        }
    }

    /**
     * The current step circled in ink: a loose rounded loop round the box, the pen running on past where it closed at the top right.
     * The box is {@code (left, top)} to {@code (right, bottom)}, inclusive.
     */
    static void loop(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int radius, int color) {
        int r = Math.min(radius, Math.min(right - left, bottom - top) / 2);
        line(graphics, left + r, top, right - r, top, color, 1, 0);
        line(graphics, left + r, bottom, right - r, bottom, color, 1, 0);
        line(graphics, left, top + r, left, bottom - r, color, 1, 0);
        line(graphics, right, top + r, right, bottom - r, color, 1, 0);
        int[][] corners = {
            {left + r, top + r, 180}, {right - r, top + r, 270}, {right - r, bottom - r, 0}, {left + r, bottom - r, 90}
        };
        for (int[] corner : corners) {
            for (int degrees = corner[2]; degrees <= corner[2] + 90; degrees++) {
                double angle = Math.toRadians(degrees);
                pixel(
                        graphics,
                        corner[0] + (int) Math.round(Math.cos(angle) * r),
                        corner[1] + (int) Math.round(Math.sin(angle) * r),
                        color);
            }
        }
        QuestMapTuning q = QuestMapTuning.current();
        line(
                graphics,
                right - q.loopTailStart(),
                top + 1,
                right - q.loopTailMid(),
                top - q.loopTailRise(),
                color,
                1,
                0);
        line(
                graphics,
                right - q.loopTailMid(),
                top - q.loopTailRise(),
                right - q.loopTailEnd(),
                top - q.loopTailEndRise(),
                color,
                1,
                0);
    }
}
