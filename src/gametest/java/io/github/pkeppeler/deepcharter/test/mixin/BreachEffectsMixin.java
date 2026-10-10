package io.github.pkeppeler.deepcharter.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;

/**
 * While {@link EvidencePins#breachFadeAlpha} is set, the breach fade shows that alpha and the HUD jitter is off. The fade runs on
 * client ticks and a frame lands anywhere in a tick, so no wait makes two shots of it equal. Test code only.
 */
@Mixin(value = BreachEffects.class, remap = false)
public abstract class BreachEffectsMixin {
	@Inject(method = "fadeAlpha", at = @At("HEAD"), cancellable = true)
	private static void deepcharter$holdTheFade(float partialTick, CallbackInfoReturnable<Float> cir) {
		float held = EvidencePins.breachFadeAlpha;
		if (held >= 0f) {
			cir.setReturnValue(held);
		}
	}

	@Inject(method = "jitter", at = @At("HEAD"), cancellable = true)
	private static void deepcharter$holdTheJitter(CallbackInfoReturnable<BreachEffects.Offset> cir) {
		if (EvidencePins.breachFadeAlpha >= 0f) {
			cir.setReturnValue(new BreachEffects.Offset(0, 0));
		}
	}
}
