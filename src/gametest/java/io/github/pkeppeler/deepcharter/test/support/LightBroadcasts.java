package io.github.pkeppeler.deepcharter.test.support;

import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * What {@code ChunkHolder.broadcastChanges} was about to send as block light for a chunk that did not tick (fed by {@code
 * ChunkHolderBroadcastMixin}). Inert until a test {@link #watch}es a chunk, and it forgets the chunk on {@link #unwatch}.
 */
public final class LightBroadcasts {
    private static final Map<Long, BitSet> SENT = new ConcurrentHashMap<>();

    private LightBroadcasts() {}

    public static void watch(ChunkPos chunk) {
        SENT.put(chunk.pack(), new BitSet());
    }

    /** The light sections, counted from the lowest, that went out for {@code chunk} since {@link #watch}. */
    public static BitSet sent(ChunkPos chunk) {
        BitSet sent = SENT.get(chunk.pack());
        if (sent == null) {
            throw new IllegalStateException("chunk " + chunk + " is not watched");
        }
        synchronized (sent) {
            return (BitSet) sent.clone();
        }
    }

    public static void unwatch(ChunkPos chunk) {
        SENT.remove(chunk.pack());
    }

    /** Called by the mixin: a holder of {@code chunk} that does not tick is broadcasting {@code blockSections}. */
    public static void record(ChunkPos chunk, BitSet blockSections) {
        BitSet sent = SENT.get(chunk.pack());
        if (sent != null) {
            synchronized (sent) {
                sent.or(blockSections);
            }
        }
    }
}
