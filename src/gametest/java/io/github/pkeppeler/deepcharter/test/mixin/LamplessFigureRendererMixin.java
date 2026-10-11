package io.github.pkeppeler.deepcharter.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.geckolib.constant.DataTickets;
import com.geckolib.renderer.base.GeoRenderState;

import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureRenderState;
import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureRenderer;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;

/**
 * While {@link EvidencePins#figureFade} is set, a lampless figure is drawn that far faded, however far it has really faded: the real
 * fade runs on the server's ticks. While {@link EvidencePins#figureAnimationAge} is set, GeckoLib reads that age as the clock of the
 * figure's animation, so the figure stands in one frame of its animation in every shot. Pods keep their own animation. Test code only.
 */
@Mixin(value = LamplessFigureRenderer.class, remap = false)
public abstract class LamplessFigureRendererMixin {
	/**
	 * GeckoLib reads the animation clock from the {@code TICK} datum of the state when there is one, and turns the pose of the
	 * controllers into data inside the base class's extract, so the datum has to be there before it runs.
	 */
	@Inject(method = "extractRenderState(Lio/github/pkeppeler/deepcharter/creature/LamplessFigure;Lio/github/pkeppeler/deepcharter/client/creature/LamplessFigureRenderState;F)V", at = @At("HEAD"))
	private void deepcharter$pinTheClock(LamplessFigure figure, LamplessFigureRenderState state, float partialTick, CallbackInfo ci) {
		if (EvidencePins.figureAnimationAge >= 0f) {
			((GeoRenderState) state).addGeckolibData(DataTickets.TICK, (double) EvidencePins.figureAnimationAge);
		}
	}

	@Inject(method = "extractRenderState(Lio/github/pkeppeler/deepcharter/creature/LamplessFigure;Lio/github/pkeppeler/deepcharter/client/creature/LamplessFigureRenderState;F)V", at = @At("RETURN"))
	private void deepcharter$fade(LamplessFigure figure, LamplessFigureRenderState state, float partialTick, CallbackInfo ci) {
		if (EvidencePins.figureFade >= 0f) {
			state.fade = EvidencePins.figureFade;
		}
	}
}
