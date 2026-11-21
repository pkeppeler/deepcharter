package io.github.pkeppeler.deepcharter.layer.mixin;

import io.github.pkeppeler.deepcharter.layer.FullChunks;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sends the light update of a full chunk that does not tick (ADR 0063). Target: {@code ServerChunkCache.broadcastChangedChunks}, at its
 * call of {@code getTickingChunk()}, which vanilla reads as "skip this holder, and forget it" when it is null. {@code
 * ChunkHolder.broadcastChanges} sends only what is marked, and only to the players that hold the chunk.
 */
@Mixin(ServerChunkCache.class)
public abstract class ChunkCacheBroadcastMixin {
    @Redirect(
            method = "broadcastChangedChunks",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ChunkHolder;getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private @Nullable LevelChunk deepcharter$fullChunkWhileNotTicking(ChunkHolder holder) {
        return FullChunks.tickingOrFull(holder);
    }
}
