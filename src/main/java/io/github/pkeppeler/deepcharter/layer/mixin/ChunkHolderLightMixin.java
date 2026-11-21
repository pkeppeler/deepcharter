package io.github.pkeppeler.deepcharter.layer.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Remembers that a light section changed while its chunk is loaded but does not tick yet (ADR 0063). Target:
 * {@code ChunkHolder.sectionLightChanged(LightLayer, int)}, at its call of {@code getTickingChunk()}, which vanilla reads as "drop the
 * change" when it is null. The chunk's holder then marks the section as vanilla does, and tells the chunk's clients once the chunk ticks.
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderLightMixin {
    @Redirect(
            method = "sectionLightChanged",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ChunkHolder;getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private @Nullable LevelChunk deepcharter$fullChunkWhileNotTicking(ChunkHolder holder) {
        LevelChunk ticking = holder.getTickingChunk();
        if (ticking != null) {
            return ticking;
        }
        return holder.getFullChunkFuture()
                .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK)
                .orElse(null);
    }
}
