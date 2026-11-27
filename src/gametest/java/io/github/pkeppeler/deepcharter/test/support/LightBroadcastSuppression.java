package io.github.pkeppeler.deepcharter.test.support;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * The chunks whose vanilla light broadcast is switched off, so that a test can make a light change that no player is told of. Fed to
 * {@code ChunkHolderLightSuppressMixin}, which makes {@code ChunkHolder.sectionLightChanged} mark nothing for such a chunk. Empty
 * unless a test {@link #suppress}es a chunk, and it must {@link #restore} it.
 */
public final class LightBroadcastSuppression {
    private static final Set<Long> SUPPRESSED = ConcurrentHashMap.newKeySet();

    private LightBroadcastSuppression() {}

    public static void suppress(ChunkPos chunk) {
        SUPPRESSED.add(chunk.pack());
    }

    public static void restore(ChunkPos chunk) {
        SUPPRESSED.remove(chunk.pack());
    }

    /** Called by the mixin. */
    public static boolean suppressed(ChunkPos chunk) {
        return !SUPPRESSED.isEmpty() && SUPPRESSED.contains(chunk.pack());
    }
}
