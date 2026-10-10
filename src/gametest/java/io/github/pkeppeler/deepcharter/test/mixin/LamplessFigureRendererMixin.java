package io.github.pkeppeler.deepcharter.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureRenderState;
import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureRenderer;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;

/**
 * While {@link EvidencePins#figureFade} is set, a lampless figure is drawn that far faded, however far it has really faded: the real
 * fade runs on the server's ticks. Test code only.
 */
@Mixin(value = LamplessFigureRenderer.class, remap = false)
public abstract class LamplessFigureRendererMixin {
	@Inject(method = "extractRenderState(Lio/github/pkeppeler/deepcharter/creature/LamplessFigure;Lio/github/pkeppeler/deepcharter/client/creature/LamplessFigureRenderState;F)V", at = @At("RETURN"))
	private void deepcharter$fade(LamplessFigure figure, LamplessFigureRenderState state, float partialTick, CallbackInfo ci) {
		if (EvidencePins.figureFade >= 0f) {
			state.fade = EvidencePins.figureFade;
		}
	}
}
