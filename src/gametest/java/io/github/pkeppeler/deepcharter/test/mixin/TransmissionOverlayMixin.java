package io.github.pkeppeler.deepcharter.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;

/**
 * While {@link EvidencePins#breachFadeAlpha} is set, transmissions stand still too, so that the text behind the held fade is not typed
 * to a different length in each run. Test code only.
 */
@Mixin(value = TransmissionOverlay.class, remap = false)
public abstract class TransmissionOverlayMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private static void deepcharter$holdTheTyping(CallbackInfo ci) {
		if (EvidencePins.breachFadeAlpha >= 0f) {
			ci.cancel();
		}
	}
}
