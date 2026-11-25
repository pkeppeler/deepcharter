package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.lang.reflect.Field;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * The slot of a container screen the mouse is over, which the screen keeps in a protected field and sets each frame: read for R and U over
 * a slot of a screen with no catalogue ({@link SlotLookup}), and forgotten under a spec sheet, so the screen's own tooltip step, which
 * runs after the catalogue draws and reads that slot, shows no tooltip. No Fabric event reaches the field, and a mixin would need an ADR
 * for one field, so it is reached by name. If a Minecraft update renames it, R and U over such a slot do nothing and the tooltip shows
 * over a sheet (logged once), and nothing else breaks; {@code CatalogueAnySlotClientTest} and {@code SpecSheetClientTest} fail, which is
 * how the rename is found.
 */
final class HoveredSlot {
    private static final String NAME = "hoveredSlot";
    private static @Nullable Field field;
    private static boolean failed;

    private HoveredSlot() {}

    /** The slot of {@code screen} under the mouse as of its last frame, or null. */
    static @Nullable Slot of(AbstractContainerScreen<?> screen) {
        Field hovered = field();
        if (hovered == null) {
            return null;
        }
        try {
            return (Slot) hovered.get(screen);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
            return null;
        }
    }

    static void clear(AbstractContainerScreen<?> screen) {
        Field hovered = field();
        if (hovered == null) {
            return;
        }
        try {
            hovered.set(screen, null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
        }
    }

    private static @Nullable Field field() {
        if (failed) {
            return null;
        }
        Field found = field;
        if (found == null) {
            try {
                found = AbstractContainerScreen.class.getDeclaredField(NAME);
                found.setAccessible(true);
                field = found;
            } catch (ReflectiveOperationException | RuntimeException e) {
                fail(e);
                return null;
            }
        }
        return found;
    }

    private static void fail(Exception e) {
        failed = true;
        DeepCharter.LOGGER.warn(
                "Cannot reach the hovered slot ({}): R and U over a container's slot do nothing, and a slot's tooltip"
                        + " shows over a spec sheet",
                NAME,
                e);
    }
}
