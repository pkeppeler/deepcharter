package io.github.pkeppeler.deepcharter.client.ui;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

/**
 * The tip of a terminal screen that says what the row under the pointer, or the row the keyboard moved to, does
 * (#1645). It reads {@link CrtButton#about}, wraps the line in a box of the CRT's own colours (the game's purple
 * tooltip would clash with the glass) and puts it where it covers no other row that sells something: under the row,
 * else over it, else beside it, else over the screen's intro text, which is never a row. It takes no room from the
 * list, so a list that scrolls keeps its rows.
 */
public final class AboutTip {
    /** The widest the box is when it goes under or over a row, and when it goes over the intro. */
    public static final int MAX_WIDTH = 200;

    private static final int INTRO_WIDTH = 260;
    /** The narrowest a box beside a row may be: narrower wraps a line to too many rows. */
    private static final int MIN_BESIDE = 120;

    /** The most rows of text a line may wrap to in a box; a placement that needs more is not used. */
    public static final int MAX_LINES = 4;

    private static final int PAD = 3;
    private static final int GAP = 2;

    /** The area of the screen the tip stays inside, and the y where its intro text starts. */
    public record Bounds(int left, int top, int right, int bottom, int introY) {}

    /** Where the box goes and what it says: its left, top, width and height, and the wrapped lines. */
    public record Box(int x, int y, int width, int height, List<String> lines) {
        /** Whether this box and the button's rectangle share any pixel. */
        public boolean covers(CrtButton button) {
            return x < button.getRight()
                    && x + width > button.getX()
                    && y < button.getBottom()
                    && y + height > button.getY();
        }
    }

    private AboutTip() {}

    /** The row the pointer is on, else the row the keyboard moved to, when it says what it does. */
    public static Optional<CrtButton> pointed(Screen screen, int mouseX, int mouseY) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof CrtButton button
                    && button.visible
                    && mouseX >= button.getX()
                    && mouseX < button.getRight()
                    && mouseY >= button.getY()
                    && mouseY < button.getBottom()
                    && button.about().isPresent()) {
                return Optional.of(button);
            }
        }
        // A click focuses a row too, and the tip must not stay open once the pointer has left it: only the keys count.
        if (Minecraft.getInstance().getLastInputType().isKeyboard()
                && screen.getFocused() instanceof CrtButton focused
                && focused.about().isPresent()) {
            return Optional.of(focused);
        }
        return Optional.empty();
    }

    /**
     * The box for {@code button}'s line, inside {@code bounds}, clear of the other rows of {@code screen} that sell
     * something.
     */
    public static Box boxFor(Font font, Bounds bounds, Screen screen, CrtButton button) {
        Predicate<Box> free = box -> inside(box, bounds)
                && box.lines().size() <= MAX_LINES
                && screen.children().stream()
                        .noneMatch(other -> other instanceof CrtButton row
                                && row != button
                                && row.visible
                                && row.about().isPresent()
                                && box.covers(row));
        int width = Math.min(MAX_WIDTH, bounds.right() - bounds.left());
        int x = Math.clamp(button.getX(), bounds.left(), Math.max(bounds.left(), bounds.right() - width));
        Box below = box(font, button, x, button.getBottom() + GAP, width, false);
        Box above = box(font, button, x, 0, width, true);
        int besideX = button.getRight() + GAP;
        int besideWidth = Math.min(MAX_WIDTH, bounds.right() - besideX);
        Box beside = box(font, button, besideX, button.getY(), Math.max(besideWidth, 1), false);
        for (Box candidate : List.of(below, above)) {
            if (free.test(candidate)) {
                return candidate;
            }
        }
        if (besideWidth >= MIN_BESIDE) {
            Box lifted = new Box(
                    beside.x(),
                    Math.max(bounds.top(), Math.min(beside.y(), bounds.bottom() - beside.height())),
                    beside.width(),
                    beside.height(),
                    beside.lines());
            if (free.test(lifted)) {
                return lifted;
            }
        }
        int introWidth = Math.min(INTRO_WIDTH, bounds.right() - bounds.left());
        return box(font, button, bounds.left(), bounds.introY(), introWidth, false);
    }

    private static boolean inside(Box box, Bounds bounds) {
        return box.x() >= bounds.left()
                && box.y() >= bounds.top()
                && box.x() + box.width() <= bounds.right()
                && box.y() + box.height() <= bounds.bottom();
    }

    /** A box of {@code width} at {@code x} and {@code y}, or, when {@code over}, ending a gap above the button. */
    private static Box box(Font font, CrtButton button, int x, int y, int width, boolean over) {
        List<String> lines = CrtText.wrap(font, button.about().orElseThrow().getString(), width - 2 * PAD);
        int height = lines.size() * (font.lineHeight + CrtTuning.current().lineSpacing()) + 2 * PAD;
        return new Box(x, over ? button.getY() - GAP - height : y, width, height, lines);
    }

    /** Draws the tip of the row pointed at, over everything else the screen drew. Nothing when no row is pointed at. */
    public static void draw(
            GuiGraphicsExtractor graphics, Font font, Screen screen, Bounds bounds, int mouseX, int mouseY) {
        pointed(screen, mouseX, mouseY).ifPresent(button -> {
            CrtTuning tuning = CrtTuning.current();
            Box box = boxFor(font, bounds, screen, button);
            graphics.fill(box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), tuning.backgroundColor());
            CrtDraw.border(
                    graphics, box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), tuning.phosphorColor());
            int step = font.lineHeight + tuning.lineSpacing();
            for (int i = 0; i < box.lines().size(); i++) {
                CrtDraw.glowText(
                        graphics,
                        font,
                        box.lines().get(i),
                        box.x() + PAD,
                        box.y() + PAD + i * step,
                        tuning.phosphorColor());
            }
        });
    }
}
