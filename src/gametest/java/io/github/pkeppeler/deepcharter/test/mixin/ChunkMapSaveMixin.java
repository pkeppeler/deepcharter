package io.github.pkeppeler.deepcharter.test.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Does not write a chunk that has not finished generating: forced chunks make the server half-generate thousands of
 * neighbours that no test touches (12,056 of 12,602 chunk holders in the #816 dump), and their writes outran the
 * level's IO thread and filled the heap. A neighbour regenerated later may lack features a full chunk beside it had
 * spilled into it; no test reads that border.
 */
@Mixin(value = ChunkMap.class, remap = false)
public abstract class ChunkMapSaveMixin {
    @Inject(
            method = "save",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;tryMarkSaved()Z"),
            cancellable = true)
    private void deepcharter$skipHalfGenerated(ChunkAccess chunk, CallbackInfoReturnable<Boolean> result) {
        if (chunk.getPersistedStatus().getChunkType() == ChunkType.PROTOCHUNK) {
            // Marked saved, as a write would have, so the next unload does not try again.
            chunk.tryMarkSaved();
            result.setReturnValue(false);
        }
    }
}
