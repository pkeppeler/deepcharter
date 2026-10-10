package io.github.pkeppeler.deepcharter.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.pkeppeler.deepcharter.creature.LamplessFigures;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;

/** While {@link EvidencePins#figureSpawningOff} is on, no tick is a spawn tick, so the level puts no figure on the rails. Test code only. */
@Mixin(value = LamplessFigures.class, remap = false)
public abstract class LamplessFiguresMixin {
	@Inject(method = "isSpawnTick", at = @At("HEAD"), cancellable = true)
	private static void deepcharter$noSpawnTick(CallbackInfoReturnable<Boolean> cir) {
		if (EvidencePins.figureSpawningOff) {
			cir.setReturnValue(false);
		}
	}
}
