package io.github.pkeppeler.deepcharter.client.ui;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

/**
 * The tip of a terminal screen that says what the row under the pointer, or the row with the keyboard focus, does
 * (#1645). It reads {@link CrtButton#about}, wraps the line in a box of the CRT's own colours (the game's purple
 * tooltip would clash with the glass) and puts it under the row, or over it when there is no room below, kept inside
 * the screen's content area. It takes no room from the list, so a list that scrolls keeps its rows.
 */
public final class AboutTip {
    /** The widest the box is, and the most rows of text a line of {@code TerminalWares} may wrap to inside it. */
    public static final int MAX_WIDTH = 200;

    public static final int MAX_LINES = 3;
    private static final int PAD = 3;
    private static final int GAP = 2;

    /** The area of the screen the tip stays inside: a terminal's content area, off its frame. */
    public record Bounds(int left, int top, int right, int bottom) {}

    /** Where the box goes and what it says: its left, top, width and height, and the wrapped lines. */
    public record Box(int x, int y, int width, int height, List<String> lines) {}

    private AboutTip() {}

    /** The row the pointer is on, else the row with the focus, when it says what it does. */
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
        return screen.getFocused() instanceof CrtButton focused
                        && focused.about().isPresent()
                ? Optional.of(focused)
                : Optional.empty();
    }

    /** The box for {@code button}'s line, kept inside {@code bounds}. */
    public static Box boxFor(Font font, Bounds bounds, CrtButton button) {
        int width = Math.min(MAX_WIDTH, bounds.right() - bounds.left());
        List<String> lines = CrtText.wrap(font, button.about().orElseThrow().getString(), width - 2 * PAD);
        int step = font.lineHeight + CrtTuning.current().lineSpacing();
        int height = lines.size() * step + 2 * PAD;
        int x = Math.clamp(button.getX(), bounds.left(), Math.max(bounds.left(), bounds.right() - width));
        int below = button.getBottom() + GAP;
        int y = below + height <= bounds.bottom() ? below : button.getY() - GAP - height;
        return new Box(x, Math.max(bounds.top(), y), width, height, lines);
    }

    /** Draws the tip of the row pointed at, over everything else the screen drew. Nothing when no row is pointed at. */
    public static void draw(
            GuiGraphicsExtractor graphics, Font font, Screen screen, Bounds bounds, int mouseX, int mouseY) {
        pointed(screen, mouseX, mouseY).ifPresent(button -> {
            CrtTuning tuning = CrtTuning.current();
            Box box = boxFor(font, bounds, button);
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
