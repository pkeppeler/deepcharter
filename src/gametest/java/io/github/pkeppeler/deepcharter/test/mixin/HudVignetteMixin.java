package io.github.pkeppeler.deepcharter.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.Hud;

import io.github.pkeppeler.deepcharter.test.support.EvidencePins;

/** While {@link EvidencePins#vignetteHidden} is on, the HUD does not draw the screen-edge vignette. Test code only. */
@Mixin(value = Hud.class, remap = false)
public abstract class HudVignetteMixin {
	@Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
	private void deepcharter$noVignette(CallbackInfo ci) {
		if (EvidencePins.vignetteHidden) {
			ci.cancel();
		}
	}
}
