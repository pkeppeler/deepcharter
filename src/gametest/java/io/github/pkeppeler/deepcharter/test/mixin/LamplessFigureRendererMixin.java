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
 * While {@link EvidencePins#figuresPosed} is on, a lampless figure is drawn at rest: no idle sway (it reads the age of the entity) and
 * no walk cycle. {@link EvidencePins#figureFade} also fixes how far it has faded. Test code only.
 */
@Mixin(value = LamplessFigureRenderer.class, remap = false)
public abstract class LamplessFigureRendererMixin {
	@Inject(method = "extractRenderState(Lio/github/pkeppeler/deepcharter/creature/LamplessFigure;Lio/github/pkeppeler/deepcharter/client/creature/LamplessFigureRenderState;F)V", at = @At("RETURN"))
	private void deepcharter$pose(LamplessFigure figure, LamplessFigureRenderState state, float partialTick, CallbackInfo ci) {
		if (!EvidencePins.figuresPosed) {
			return;
		}
		state.ageInTicks = 0f;
		state.walkAnimationPos = 0f;
		state.walkAnimationSpeed = 0f;
		if (EvidencePins.figureFade >= 0f) {
			state.fade = EvidencePins.figureFade;
		}
	}
}
