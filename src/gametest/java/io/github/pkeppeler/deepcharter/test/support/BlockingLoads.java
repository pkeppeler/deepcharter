package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The chunks the server thread waited for while a test held this armed: each is a synchronous load, which a streamed arrival area must
 * not need (#1159). Fed by {@code ServerChunkCacheBlockingLoadMixin}, inert until {@link #arm}. One test arms it at a time; the test's
 * finish ({@link #release}) disarms it, passed or failed, and one test's finish leaves another's arming alone.
 */
public final class BlockingLoads {
    /** A chunk the server thread loaded by waiting. */
    public record Load(ResourceKey<Level> level, int chunkX, int chunkZ) {}

    private static final List<Load> LOADS = new ArrayList<>();
    /** The bounds of the test that armed this, or null when it is not armed. Server thread only. */
    private static @Nullable AABB owner;

    private BlockingLoads() {}

    /** Starts recording for the test with these bounds. Fails when another test has it armed. */
    public static void arm(AABB testBounds) {
        if (owner != null && !owner.equals(testBounds)) {
            throw new IllegalStateException("BlockingLoads is armed by another test");
        }
        owner = testBounds;
        LOADS.clear();
    }

    /** Stops recording and returns what was recorded. */
    public static List<Load> disarm() {
        List<Load> loads = List.copyOf(LOADS);
        LOADS.clear();
        owner = null;
        return loads;
    }

    /** Whether nothing is armed: a step that disarms and fails is run again, and must not read the record twice. */
    public static boolean disarmed() {
        return owner == null;
    }

    /** Disarms when {@code testBounds} armed it; called when a test finishes. */
    public static void release(AABB testBounds) {
        if (owner != null && owner.equals(testBounds)) {
            disarm();
        }
    }

    /** Called by the mixin, on the server thread. */
    public static void record(ResourceKey<Level> level, int chunkX, int chunkZ) {
        if (owner != null) {
            LOADS.add(new Load(level, chunkX, chunkZ));
        }
    }
}
