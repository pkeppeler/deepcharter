package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import java.lang.reflect.Field;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Moves a container screen's inventory image down, for the monitor form that stands over it (#846). The screen keeps the image's top in
 * a protected field no Fabric event or accessor gives, and every slot, the player model and the click tests read it, so setting it moves
 * the whole image; a resize builds the screen again and the field is set again after that.
 *
 * <p>By reflection, as the client tests read these fields: the game ships with its own names, so one that moved fails loud, here. If the
 * monitor form is the one kept, this becomes an accessor mixin with its ADR.
 */
final class ScreenTop {
    private static final Field TOP = field();

    private ScreenTop() {}

    private static Field field() {
        try {
            Field field = AbstractContainerScreen.class.getDeclaredField("topPos");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new LinkageError(
                    "a container screen has no topPos; the catalogue's monitor form moves it by name", e);
        }
    }

    /** Puts the top of {@code screen}'s image at {@code top}, if it is not there. */
    static void move(AbstractContainerScreen<?> screen, int top) {
        try {
            if (TOP.getInt(screen) != top) {
                TOP.setInt(screen, top);
            }
        } catch (IllegalAccessException e) {
            throw new LinkageError("the catalogue could not move a container screen's image", e);
        }
    }
}
