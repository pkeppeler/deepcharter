package io.github.pkeppeler.deepcharter.client.handbook.mixin;

import java.util.List;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the toasts the manager is showing, which it keeps private; {@code VanillaToasts} lists the ones that are not step popups (ADR 0084). */
@Mixin(ToastManager.class)
public interface ToastManagerAccess {
    /** The manager's toast instances, each readable as a {@link ToastInstanceAccess}. */
    @Accessor("visibleToasts")
    List<?> deepcharter$visibleToasts();
}
