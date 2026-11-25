package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.LightEngineGate;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes {@code waitForPendingTasks} for a position {@link LightEngineGate} lists complete only once the gate is
 * released. Inert otherwise. Test code only.
 */
@Mixin(value = ThreadedLevelLightEngine.class, remap = false)
public abstract class LightEngineGateMixin {
    @Inject(method = "waitForPendingTasks", at = @At("RETURN"), cancellable = true)
    private void deepcharter$gate(int x, int z, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        CompletableFuture<Void> gate = LightEngineGate.gate(x, z);
        if (gate != null) {
            cir.setReturnValue(cir.getReturnValue().thenCombine(gate, (done, open) -> null));
        }
    }
}
