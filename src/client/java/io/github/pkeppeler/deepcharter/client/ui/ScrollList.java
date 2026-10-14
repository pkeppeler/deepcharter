package io.github.pkeppeler.deepcharter.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The scroll state of a terminal list that is longer than its screen, and everything a screen does with it: the first row shown, its clamping, the "a-b OF n" hint,
 * the narration that tells a player who cannot see the screen that there are more rows, the mouse wheel, and the keys. A button off the screen cannot take focus, so
 * Page Up and Page Down move the list a page, and Down or Tab from the last shown row (Up or Shift+Tab from the first) moves it one row and focuses the row that came
 * into view. The screen keeps what a row looks like and means.
 */
public final class ScrollList {
    /** Empty for a list that shows no hint. */
    private final Optional<String> hintKey;

    private final IntSupplier total;
    private final IntSupplier visible;
    private final Runnable changed;
    private final List<CrtButton> shown = new ArrayList<>();
    private int first;

    /**
     * @param hintKey the lang key of the hint, with the first row, the last row and the total as its arguments
     * @param total how many rows the list has
     * @param visible how many rows fit on the screen
     * @param changed runs when the first row shown changes, so the screen lays its buttons out again
     */
    public ScrollList(String hintKey, IntSupplier total, IntSupplier visible, Runnable changed) {
        this(Optional.of(hintKey), total, visible, changed);
    }

    /** A list that shows no hint, such as one the wheel scrolls and nothing else says is longer than the sheet. */
    public ScrollList(IntSupplier total, IntSupplier visible, Runnable changed) {
        this(Optional.empty(), total, visible, changed);
    }

    private ScrollList(Optional<String> hintKey, IntSupplier total, IntSupplier visible, Runnable changed) {
        this.hintKey = hintKey;
        this.total = total;
        this.visible = visible;
        this.changed = changed;
    }

    /** The index of the first row shown. */
    public int first() {
        return first;
    }

    /** Starts a layout: keeps the first row inside the list, which may have changed size, and forgets the buttons of the last layout. */
    public void beginLayout() {
        first = clamp(first);
        shown.clear();
    }

    /** Records the button of the next row shown, top to bottom, for the keys to find the focus in. */
    public <B extends CrtButton> B shown(B button) {
        shown.add(button);
        return button;
    }

    /** Shows the list from row {@code row}, kept inside the list, and lays the buttons out again if that moved it. */
    public void scrollTo(int row) {
        int clamped = clamp(row);
        if (clamped != first) {
            first = clamped;
            changed.run();
        }
    }

    private int clamp(int row) {
        return Mth.clamp(row, 0, Math.max(0, total.getAsInt() - visible.getAsInt()));
    }

    /** Which rows are shown, when the list is longer than the screen. */
    public Optional<String> hint() {
        int rows = visible.getAsInt();
        if (hintKey.isEmpty() || rows >= total.getAsInt()) {
            return Optional.empty();
        }
        return Optional.of(Component.translatable(hintKey.get(), first + 1, first + rows, total.getAsInt())
                .getString());
    }

    /** The screen's own narration, with the hint after it when there is one. */
    public Component narrate(Component narration) {
        return hint().<Component>map(hint -> Component.literal(narration.getString() + ". " + hint))
                .orElse(narration);
    }

    /** Scrolls a row to the wheel; false when it did not turn. */
    public boolean mouseScrolled(double scrollY) {
        if (scrollY == 0) {
            return false;
        }
        scrollTo(first - (int) Math.signum(scrollY));
        return true;
    }

    /** Scrolls to a key and moves the focus of {@code screen}; false when the key is not the list's. */
    public boolean keyPressed(KeyEvent event, ContainerEventHandler screen) {
        int focused = shown.indexOf(screen.getFocused());
        boolean forward = event.isDown() || (event.isCycleFocus() && !event.hasShiftDown());
        boolean backward = event.isUp() || (event.isCycleFocus() && event.hasShiftDown());
        if (focused >= 0 && forward && focused == shown.size() - 1 && first + shown.size() < total.getAsInt()) {
            scrollTo(first + 1);
            screen.setFocused(shown.getLast());
            return true;
        }
        if (focused == 0 && backward && first > 0) {
            scrollTo(first - 1);
            screen.setFocused(shown.getFirst());
            return true;
        }
        if (event.key() == InputConstants.KEY_PAGEDOWN) {
            scrollTo(first + visible.getAsInt());
            return true;
        }
        if (event.key() == InputConstants.KEY_PAGEUP) {
            scrollTo(first - visible.getAsInt());
            return true;
        }
        return false;
    }
}
