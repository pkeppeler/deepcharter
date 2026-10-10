package io.github.pkeppeler.deepcharter.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.Hud;

import io.github.pkeppeler.deepcharter.test.support.EvidencePins;

/**
 * Leaves out of the HUD the two things in it that fade or ease on client ticks: the screen-edge vignette
 * ({@link EvidencePins#vignetteHidden}) and the name of the held item that shows for two seconds after a slot changes
 * ({@link EvidencePins#itemNameHidden}). Test code only.
 */
@Mixin(value = Hud.class, remap = false)
public abstract class HudMixin {
	@Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
	private void deepcharter$noVignette(CallbackInfo ci) {
		if (EvidencePins.vignetteHidden) {
			ci.cancel();
		}
	}

	@Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
	private void deepcharter$noItemName(CallbackInfo ci) {
		if (EvidencePins.itemNameHidden) {
			ci.cancel();
		}
	}
}
