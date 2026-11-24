package io.github.pkeppeler.deepcharter.client.handbook.mixin;

import net.minecraft.client.gui.components.toasts.Toast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads a toast and its first slot out of the manager's package-private {@code ToastInstance} (ADR 0084). */
@Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastManager$ToastInstance")
public interface ToastInstanceAccess {
    @Accessor("toast")
    Toast deepcharter$toast();

    @Accessor("firstSlotIndex")
    int deepcharter$firstSlotIndex();
}
