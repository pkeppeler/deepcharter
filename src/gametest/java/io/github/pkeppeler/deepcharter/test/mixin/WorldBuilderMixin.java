package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.WorldBeforeItems;
import net.fabricmc.fabric.impl.client.gametest.world.TestWorldBuilderImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells {@link WorldBeforeItems} when a class's own {@code worldBuilder().create()} or {@code createServer} runs and returns, so the world
 * load is told apart from a test's read of item components. Test code only.
 */
@Mixin(value = TestWorldBuilderImpl.class, remap = false)
public abstract class WorldBuilderMixin {
    @Inject(method = {"create", "createServer"}, at = @At("HEAD"))
    private void deepcharter$creating(CallbackInfoReturnable<?> cir) {
        WorldBeforeItems.creating();
    }

    @Inject(method = {"create", "createServer"}, at = @At("RETURN"))
    private void deepcharter$created(CallbackInfoReturnable<?> cir) {
        WorldBeforeItems.created();
    }
}
