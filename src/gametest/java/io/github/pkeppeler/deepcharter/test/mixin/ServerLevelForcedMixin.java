package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The GameTest runner unforces every forced chunk at each batch end; a {@link FarChunks#pin}ned chunk refuses it (#669). */
@Mixin(value = ServerLevel.class, remap = false)
public abstract class ServerLevelForcedMixin {
    @Inject(method = "setChunkForced", at = @At("HEAD"), cancellable = true)
    private void deepcharter$keepPinnedChunksForced(
            int chunkX, int chunkZ, boolean forced, CallbackInfoReturnable<Boolean> result) {
        if (!forced && FarChunks.isPinned((ServerLevel) (Object) this, chunkX, chunkZ)) {
            result.setReturnValue(false);
        }
    }
}
