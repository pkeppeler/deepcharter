package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.ClientInstances;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.impl.client.gametest.FabricClientGameTestRunner;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a client JVM to its slice of the client classes when CI runs several side by side (#1722, {@link
 * ClientInstances}). Inert unless {@code -Ddeepcharter.clientInstance} is set. A rename of the target fails the run at
 * launch ({@code defaultRequire: 1}), so the instances cannot fall back to each running every class.
 */
@Mixin(value = FabricClientGameTestRunner.class, remap = false)
public abstract class FabricClientGameTestRunnerMixin {
    @Inject(method = "getTestToRun", at = @At("RETURN"), cancellable = true)
    private static void deepcharter$keepThisInstancesSlice(
            CallbackInfoReturnable<List<EntrypointContainer<FabricClientGameTest>>> cir) {
        cir.setReturnValue(ClientInstances.keep(cir.getReturnValue()));
    }
}
