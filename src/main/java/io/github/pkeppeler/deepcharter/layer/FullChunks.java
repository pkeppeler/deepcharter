package io.github.pkeppeler.deepcharter.layer;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/** The chunk of a holder as the light update of ADR 0063 sees it: the ticking chunk, else the full chunk that does not tick yet. */
public final class FullChunks {
    private FullChunks() {}

    /** The holder's ticking chunk, or its full chunk while that does not tick, or null while the chunk is not full. */
    public static @Nullable LevelChunk tickingOrFull(ChunkHolder holder) {
        LevelChunk ticking = holder.getTickingChunk();
        if (ticking != null) {
            return ticking;
        }
        return holder.getFullChunkFuture()
                .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK)
                .orElse(null);
    }
}
