package io.github.pkeppeler.deepcharter.test.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Does not write a chunk that has not finished generating. A forced chunk makes the server generate the chunks around it, up to the
 * 17 x 17 that its own generation needs, and only the forced one ever becomes a full chunk that a test can touch. In a 2352-row
 * campaign world that is 12,000 half-generated chunks of 90 KB for 546 full ones, and unloading them queued 6,259 writes (1.07 GB of
 * NBT) on the level's one IO thread, which was still writing them in the next batch (#816). A half-generated chunk holds only what
 * the world seed makes again, so the next force generates it again, as vanilla does for a chunk its crash lost. A full chunk is
 * written as before: the tests that unload a chunk and force it back (such as {@code UnloadedChunk}) rely on that.
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
