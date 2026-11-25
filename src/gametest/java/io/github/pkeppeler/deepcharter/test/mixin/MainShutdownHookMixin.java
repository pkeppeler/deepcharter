package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.DedicatedServerRelease;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reports the "Server Shutdown Thread" that {@code Main.main} registers for a dedicated server (the anonymous {@code Main$1}) to
 * {@link DedicatedServerRelease} (#914). If a Minecraft update breaks it, fix the target; do not remove the mixin.
 */
@Mixin(targets = "net.minecraft.server.Main$1", remap = false)
public abstract class MainShutdownHookMixin extends Thread {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void deepcharter$report(String name, DedicatedServer server, CallbackInfo ci) {
        DedicatedServerRelease.registered(server, this);
    }
}
