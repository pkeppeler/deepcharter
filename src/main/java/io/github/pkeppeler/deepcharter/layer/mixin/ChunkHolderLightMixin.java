package io.github.pkeppeler.deepcharter.layer.mixin;

import io.github.pkeppeler.deepcharter.layer.FullChunks;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Marks a light section that changed while its chunk is full but does not tick (ADR 0063). Target:
 * {@code ChunkHolder.sectionLightChanged(LightLayer, int)}, at its call of {@code getTickingChunk()}, which vanilla reads as "drop the
 * change" when it is null. {@link ChunkCacheBroadcastMixin} then sends what is marked.
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
        return FullChunks.tickingOrFull(holder);
    }
}
