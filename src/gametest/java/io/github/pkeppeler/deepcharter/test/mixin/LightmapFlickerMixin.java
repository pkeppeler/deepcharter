package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While {@link EvidencePins#lightFlickerOff} is on, the block light does not flicker. Vanilla moves the brightness of every
 * block-lit surface (lamps, candles, a pod's lights) by a random walk on each client tick, so two shots of one lit place differ.
 * Test code only.
 */
@Mixin(value = LightmapRenderStateExtractor.class, remap = false)
public abstract class LightmapFlickerMixin {
    @Shadow
    private float blockLightFlicker;

    @Shadow
    private boolean needsUpdate;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void deepcharter$noFlicker(CallbackInfo ci) {
        if (EvidencePins.lightFlickerOff) {
            blockLightFlicker = 0f;
            needsUpdate = true;
            ci.cancel();
        }
    }
}
