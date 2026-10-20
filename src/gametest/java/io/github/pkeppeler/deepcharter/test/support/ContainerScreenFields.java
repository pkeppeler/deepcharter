package io.github.pkeppeler.deepcharter.test.support;

import java.lang.reflect.Field;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/** Reads the layout fields of a container screen ({@code leftPos}, {@code imageWidth} and the like), which it keeps protected. */
public final class ContainerScreenFields {
    private ContainerScreenFields() {}

    public static int intField(AbstractContainerScreen<?> screen, String name) {
        try {
            Field field = AbstractContainerScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(screen);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("a container screen has no field " + name + "; the test reads it by name", e);
        }
    }
}
