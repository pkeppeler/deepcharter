package io.github.pkeppeler.deepcharter.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.GameRenderer;

import io.github.pkeppeler.deepcharter.test.support.EvidencePins;

/** While {@link EvidencePins#handsHidden} is on, the first-person hands and held item are not drawn. Test code only. */
@Mixin(value = GameRenderer.class, remap = false)
public abstract class GameRendererHandsMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void deepcharter$noHands(CallbackInfo ci) {
		if (EvidencePins.handsHidden) {
			ci.cancel();
		}
	}
}
