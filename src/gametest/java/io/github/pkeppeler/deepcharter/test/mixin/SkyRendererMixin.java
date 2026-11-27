package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While {@link EvidencePins#skyHidden} is on, the sky pass draws nothing: no sky disc, stars, sun, moon or sunrise. The
 * frame is cleared to the fog colour before it, so the sky reads as fog. The pass returns just after it sets the shader
 * fog, which it does for the passes after it too. Test code only.
 */
@Mixin(value = SkyRenderer.class, remap = false)
public abstract class SkyRendererMixin {
    @Inject(
            method = "render",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFog(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V",
                            shift = At.Shift.AFTER),
            cancellable = true)
    private void deepcharter$noSky(CallbackInfo ci) {
        if (EvidencePins.skyHidden) {
            ci.cancel();
        }
    }
}
