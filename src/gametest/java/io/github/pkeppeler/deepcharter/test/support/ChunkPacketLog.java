package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The chunk packets the client handles, with the level it handled each in, while a test has it armed. Read by
 * {@code ClientPacketListenerMixin}; inert until {@link #arm}, and a test calls {@link #disarm} in a {@code finally}. A chunk that
 * arrives as a seam payload is not a chunk packet and is not logged: what is logged is what vanilla's own send put on the wire. Client
 * thread to record, any thread to read.
 */
public final class ChunkPacketLog {
    /** One handled chunk packet: the level the client was in and the chunk. */
    public record Entry(ResourceKey<Level> level, int x, int z) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean armed;

    private ChunkPacketLog() {}

    /** Starts a fresh log. */
    public static synchronized void arm() {
        ENTRIES.clear();
        armed = true;
    }

    /** Stops logging and returns what was logged, oldest first. */
    public static synchronized List<Entry> disarm() {
        armed = false;
        List<Entry> logged = List.copyOf(ENTRIES);
        ENTRIES.clear();
        return logged;
    }

    /** What has been logged since {@link #arm}, oldest first. */
    public static synchronized List<Entry> entries() {
        return List.copyOf(ENTRIES);
    }

    public static synchronized void record(ResourceKey<Level> level, int x, int z) {
        if (armed) {
            ENTRIES.add(new Entry(level, x, z));
        }
    }
}
