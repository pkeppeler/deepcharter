package io.github.pkeppeler.deepcharter.test.mixin;

import com.geckolib.constant.DataTickets;
import com.geckolib.renderer.base.GeoRenderState;
import io.github.pkeppeler.deepcharter.client.creature.DrifterRenderState;
import io.github.pkeppeler.deepcharter.client.creature.DrifterRenderer;
import io.github.pkeppeler.deepcharter.creature.Drifter;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While {@link EvidencePins#drifterAnimationAge} is set, the animation clock of a drifter is held at that age, so each
 * still of its loop shows the same pose. Test code only; the lampless figure's pin is {@link
 * LamplessFigureRendererMixin}.
 */
@Mixin(value = DrifterRenderer.class, remap = false)
public abstract class DrifterRendererMixin {
    /**
     * The clock is the {@code TICK} datum of the state, which has to be there before the base class's extract turns the
     * pose into data.
     */
    @Inject(
            method =
                    "extractRenderState(Lio/github/pkeppeler/deepcharter/creature/Drifter;Lio/github/pkeppeler/deepcharter/client/creature/DrifterRenderState;F)V",
            at = @At("HEAD"))
    private void deepcharter$pinTheClock(
            Drifter drifter, DrifterRenderState state, float partialTick, CallbackInfo ci) {
        if (EvidencePins.drifterAnimationAge >= 0f) {
            ((GeoRenderState) state).addGeckolibData(DataTickets.TICK, (double) EvidencePins.drifterAnimationAge);
        }
    }
}
