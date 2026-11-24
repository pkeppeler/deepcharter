package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * The chunks the server thread waited for while a test held this armed: each is a synchronous load, which a streamed arrival area must
 * not need (#1159). Fed by {@code ServerChunkCacheBlockingLoadMixin}, inert until {@link #arm}. Every armed test is given every wait the
 * server makes, so a test filters them to its own chunks; the test's finish ({@link #release}) disarms it, passed or failed.
 */
public final class BlockingLoads {
    /** A chunk the server thread loaded by waiting. */
    public record Load(ResourceKey<Level> level, int chunkX, int chunkZ) {}

    /** The waits seen since each armed test armed, by the test's bounds. Server thread only. */
    private static final Map<AABB, List<Load>> ARMED = new HashMap<>();

    private BlockingLoads() {}

    /** Starts recording for the test with these bounds. */
    public static void arm(AABB testBounds) {
        ARMED.put(testBounds, new ArrayList<>());
    }

    /** Whether the test with these bounds has no recording going: it never armed, or has taken its record. */
    public static boolean disarmed(AABB testBounds) {
        return !ARMED.containsKey(testBounds);
    }

    /** Stops recording for the test and returns what it saw. */
    public static List<Load> disarm(AABB testBounds) {
        List<Load> loads = ARMED.remove(testBounds);
        return loads == null ? List.of() : List.copyOf(loads);
    }

    /** Disarms the test with these bounds if it is armed; called when a test finishes. */
    public static void release(AABB testBounds) {
        ARMED.remove(testBounds);
    }

    /** Called by the mixin, on the server thread. */
    public static void record(ResourceKey<Level> level, int chunkX, int chunkZ) {
        Load load = new Load(level, chunkX, chunkZ);
        ARMED.values().forEach(loads -> loads.add(load));
    }
}
