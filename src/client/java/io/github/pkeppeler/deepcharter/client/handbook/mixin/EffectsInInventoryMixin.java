package io.github.pkeppeler.deepcharter.client.handbook.mixin;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps vanilla's effect list off the screen while the parts catalogue stands over it (ADR 0094). Target: {@code
 * EffectsInInventory.extractRenderState}, at its head. The catalogue draws its own row of effects, so the list under
 * its console is never seen, and its hover tooltip would still be. {@code canSeeEffects} is left alone, so the HUD's
 * own list stays hidden as in vanilla.
 */
@Mixin(EffectsInInventory.class)
public abstract class EffectsInInventoryMixin {
    @Shadow
    @Final
    private AbstractContainerScreen<?> screen;

    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
            at = @At("HEAD"),
            cancellable = true)
    private void deepcharter$notUnderTheCatalogue(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        @Nullable CataloguePanel panel = CatalogueScreens.panelOf(screen);
        if (panel != null && panel.panelsShown()) {
            ci.cancel();
        }
    }
}
