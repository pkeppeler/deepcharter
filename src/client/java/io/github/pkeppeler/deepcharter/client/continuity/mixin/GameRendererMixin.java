package io.github.pkeppeler.deepcharter.client.continuity.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.pkeppeler.deepcharter.client.continuity.ContinuityClient;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No red hurt tilt while the Continuity Event plays (#1337, ADR 0100): the death is a hard cut, not a fall. Target: {@code
 * GameRenderer.bobHurt}, the camera roll vanilla adds for the hurt and death times, skipped while the sequence is under way.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(
            method =
                    "bobHurt(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void deepcharter$noTiltInTheEvent(CameraRenderState camera, PoseStack pose, CallbackInfo ci) {
        if (ContinuityClient.inSequence()) {
            ci.cancel();
        }
    }
}
