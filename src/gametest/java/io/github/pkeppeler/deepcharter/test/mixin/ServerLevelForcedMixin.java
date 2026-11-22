package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a chunk that {@link FarChunks#pin} holds forced. When the last test of a batch finishes, the GameTest runner unforces every
 * forced chunk of the level, not only the ones its own tests forced, so the colony pad was loose after the first batch and the
 * next test to need it met an unloaded chunk (#669).
 */
@Mixin(value = ServerLevel.class, remap = false)
public abstract class ServerLevelForcedMixin {
    @Inject(method = "setChunkForced", at = @At("HEAD"), cancellable = true)
    private void deepcharter$keepPinnedChunksForced(
            int chunkX, int chunkZ, boolean forced, CallbackInfoReturnable<Boolean> result) {
        if (!forced && FarChunks.isPinned((ServerLevel) (Object) this, chunkX, chunkZ)) {
            // false: nothing changed, as for a chunk that was not forced.
            result.setReturnValue(false);
        }
    }
}
