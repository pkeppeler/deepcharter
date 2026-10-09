package io.github.pkeppeler.deepcharter.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.impl.client.gametest.util.ClientGameTestImpl;

import io.github.pkeppeler.deepcharter.test.support.WorldLoadWait;

/**
 * Replaces Fabric's world-load wait, a fixed 1200-tick loop that fails with "Timeout loading world" (issue 277), with the wall-clock
 * {@link WorldLoadWait#await}. Test code only: this mixin is in the gametest mod and never ships. If a Fabric update renames the target,
 * the mixin fails to apply and the client run stops at launch, so the wait cannot silently go back to ticks.
 */
@Mixin(value = ClientGameTestImpl.class, remap = false)
public abstract class ClientGameTestImplMixin {
	@Inject(method = "waitForWorldLoad", at = @At("HEAD"), cancellable = true)
	private static void deepcharter$waitOnTheWallClock(ClientGameTestContext context, CallbackInfo ci) {
		WorldLoadWait.await(context);
		ci.cancel();
	}
}
