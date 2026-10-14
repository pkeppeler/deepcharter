package io.github.pkeppeler.deepcharter.layer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Light is computed off the server thread, and vanilla drops the light update of a chunk that is not yet ticking (issue 427), so a client
 * sent a chunk while light is pending keeps the old light; call this after the last block placed in a chunk a player may be sent.
 */
public final class LightSync {
    private LightSync() {}

    /** As {@link #holdSendUntilLit(ServerLevel, ChunkPos)}, for every chunk the box touches. */
    public static void holdSendUntilLit(ServerLevel level, BlockPos min, BlockPos max) {
        ChunkPos.rangeClosed(ChunkPos.containing(min), ChunkPos.containing(max))
                .forEach(chunk -> holdSendUntilLit(level, chunk));
    }

    /**
     * Holds the sending of the chunk, and of each of its eight neighbours that has a loaded holder (light spreads at most 15 blocks, so
     * one ring is enough), until the light tasks queued so far are done. It is vanilla's {@code ChunkMap.waitForLightBeforeSending}.
     */
    public static void holdSendUntilLit(ServerLevel level, ChunkPos chunk) {
        level.getChunkSource().chunkMap.waitForLightBeforeSending(chunk, 0);
    }
}
