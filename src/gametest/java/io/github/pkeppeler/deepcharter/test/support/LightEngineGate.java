package io.github.pkeppeler.deepcharter.test.support;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

/**
 * Chunk positions whose {@code ThreadedLevelLightEngine.waitForPendingTasks} future a test holds back, so "the light
 * tasks queued at this chunk have not run yet" is a state the test sets. Fed to {@code LightEngineGateMixin}. Empty
 * unless a test {@link #close}s a position, and it must {@link #release} it.
 */
public final class LightEngineGate {
    private static final Map<Long, CompletableFuture<Void>> GATES = new ConcurrentHashMap<>();

    private LightEngineGate() {}

    /** Holds back every wait for the light tasks of the chunk requested from now on. */
    public static void close(ChunkPos chunk) {
        GATES.put(chunk.pack(), new CompletableFuture<>());
    }

    /** Lets the waits requested while the gate was closed complete as the light engine does. */
    public static void release(ChunkPos chunk) {
        CompletableFuture<Void> gate = GATES.remove(chunk.pack());
        if (gate != null) {
            gate.complete(null);
        }
    }

    /** Called by the mixin: the gate of the position, or null. */
    public static @Nullable CompletableFuture<Void> gate(int x, int z) {
        return GATES.isEmpty() ? null : GATES.get(ChunkPos.pack(x, z));
    }
}
