package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.lang.reflect.Field;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Forgets which slot of a container screen the mouse is over, so the screen's own tooltip step, which runs after the catalogue draws
 * and reads that slot, shows no tooltip. The field is protected and no Fabric event reaches it, and a mixin would need an ADR for one
 * field, so it is cleared by name. If a Minecraft update renames it, the tooltip shows over a sheet (logged once) and nothing else breaks;
 * {@code SpecSheetClientTest} fails, which is how the rename is found.
 */
final class HoveredSlot {
    private static final String NAME = "hoveredSlot";
    private static boolean failed;

    private HoveredSlot() {}

    static void clear(AbstractContainerScreen<?> screen) {
        if (failed) {
            return;
        }
        try {
            Field field = AbstractContainerScreen.class.getDeclaredField(NAME);
            field.setAccessible(true);
            field.set(screen, null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            DeepCharter.LOGGER.warn(
                    "Cannot clear the hovered slot ({}): a slot's tooltip shows over a spec sheet", NAME, e);
        }
    }
}
