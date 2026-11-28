package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.WorldBeforeItems;
import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes {@code Holder.Reference.components()}, which every item stack's creation reads, fail for a client test class that has not joined a world ({@link WorldBeforeItems}). Test code only. */
@Mixin(value = Holder.Reference.class, remap = false)
public abstract class WorldBeforeItemsMixin {
    @Inject(method = "components", at = @At("HEAD"))
    private void deepcharter$worldBeforeItems(CallbackInfoReturnable<?> cir) {
        WorldBeforeItems.check();
    }
}
