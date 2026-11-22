package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.LightBroadcastSuppression;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes {@code ChunkHolder.sectionLightChanged} mark nothing for a chunk that {@link LightBroadcastSuppression} lists, so vanilla sends
 * no light update for it: the case of {@code LightSyncSafetyNetTest}, a light change that no broadcast reaches a player. Inert
 * otherwise. Test code only.
 */
@Mixin(value = ChunkHolder.class, remap = false)
public abstract class ChunkHolderLightSuppressMixin {
    @Inject(method = "sectionLightChanged", at = @At("HEAD"), cancellable = true)
    private void deepcharter$suppress(LightLayer layer, int sectionY, CallbackInfoReturnable<Boolean> cir) {
        if (LightBroadcastSuppression.suppressed(((ChunkHolder) (Object) this).getPos())) {
            cir.setReturnValue(false);
        }
    }
}
