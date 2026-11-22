package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.LightSync;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/**
 * Awaits entity ticking in a far chunk of a fresh world. The test method must call this, never a tick
 * callback. It forces the chunk, because a mock player teleported within its own dimension never loads
 * chunks around itself. A chunk it forces is released when the test finishes ({@link #release}): a forced chunk pins the
 * generation neighbourhood around it (up to 17 x 17 chunks of proto chunks and their sections), so one left forced for the rest
 * of the run grows the heap with the suite (#569).
 */
public final class FarChunks {
    /** Wall-clock seconds that a wait for a chunk may take. The GameTest server ticks unthrottled, so a tick budget alone is only seconds of real time. */
    public static final int WAIT_SECONDS = 120;
    /** Milliseconds slept per waiting tick, so the chunk generation threads get CPU. */
    public static final int POLL_MILLIS = 2;
    /**
     * A test's {@code maxTicks} for one wait. Each waiting tick takes at least {@link #POLL_MILLIS}, so no more than
     * this many ticks pass in {@link #WAIT_SECONDS}, and the wall-clock failure always fires before the GameTest limit.
     * Add the ticks of the test's own work. A test with a second wait adds this once more.
     */
    public static final int AWAIT_BUDGET_TICKS = WAIT_SECONDS * 1000 / POLL_MILLIS + 600;

    /** The server tick on which a waiter last slept. Server thread only. */
    private static int lastSleptTick = -1;

    /** A chunk of a level that tests hold forced, and how many of them hold it. */
    private record Chunk(ServerLevel level, long pos) {}

    private static final Map<Chunk, Integer> HOLDS = new HashMap<>();
    /** The chunks each running test holds, by the test's bounds (what {@code GameTestHelper.getBounds} and the runner share). */
    private static final Map<AABB, List<Chunk>> HELD_BY_TEST = new HashMap<>();
    /** The chunks {@link #pin} holds for the world's life. */
    private static final Set<Chunk> PINNED = new HashSet<>();

    static {
        // A world's levels must not outlive its server. A pinned chunk's record holds its ServerLevel, the colony pad's
        // chunks are pinned in every
        // world, and a client run makes a server for each test class: 6 levels and 70 MB of each world stayed on the
        // heap until it ran out (#803).
        ServerLifecycleEvents.SERVER_STOPPED.register(FarChunks::forget);
    }

    private FarChunks() {}

    /** How many chunks tests hold forced or pinned in the servers that run. */
    public static int heldCount() {
        return HOLDS.size() + PINNED.size();
    }

    private static void forget(MinecraftServer server) {
        HOLDS.keySet().removeIf(chunk -> chunk.level().getServer() == server);
        PINNED.removeIf(chunk -> chunk.level().getServer() == server);
        HELD_BY_TEST
                .values()
                .forEach(chunks -> chunks.removeIf(chunk -> chunk.level().getServer() == server));
        HELD_BY_TEST.values().removeIf(List::isEmpty);
    }

    /**
     * Forces a chunk until {@code helper}'s test finishes. A chunk that is forced already and that no test holds (a colony's pad,
     * the test's own structure) is left as it is, and so is its owner's release. Server thread only.
     */
    public static void force(GameTestHelper helper, ServerLevel level, int chunkX, int chunkZ) {
        force(helper.getBounds(), level, chunkX, chunkZ);
    }

    /** As {@link #force(GameTestHelper, ServerLevel, int, int)}, for the test with these bounds; {@link FarChunksTest} drives two. */
    public static void force(AABB testBounds, ServerLevel level, int chunkX, int chunkZ) {
        Chunk chunk = new Chunk(level, ChunkPos.pack(chunkX, chunkZ));
        List<Chunk> mine = HELD_BY_TEST.computeIfAbsent(testBounds, bounds -> new ArrayList<>());
        if (mine.contains(chunk)) {
            return;
        }
        boolean forcedNow = level.setChunkForced(chunkX, chunkZ, true);
        if (forcedNow || HOLDS.containsKey(chunk)) {
            HOLDS.merge(chunk, 1, Integer::sum);
            mine.add(chunk);
        }
    }

    /** Forces a chunk for the rest of the world, since the runner unforces every forced chunk at each batch end (#669). */
    public static void pin(ServerLevel level, int chunkX, int chunkZ) {
        PINNED.add(new Chunk(level, ChunkPos.pack(chunkX, chunkZ)));
        level.setChunkForced(chunkX, chunkZ, true);
    }

    /** Whether {@link #pin} holds this chunk, exempt from the runner's batch-end unforce. */
    public static boolean isPinned(ServerLevel level, int chunkX, int chunkZ) {
        return PINNED.contains(new Chunk(level, ChunkPos.pack(chunkX, chunkZ)));
    }

    /**
     * Lets go of a chunk {@code helper}'s test holds, before the test ends, so the test can wait for it to unload and {@link #force} it
     * again. A chunk the test does not hold is left as it is. Server thread only.
     */
    public static void release(GameTestHelper helper, ServerLevel level, int chunkX, int chunkZ) {
        Chunk chunk = new Chunk(level, ChunkPos.pack(chunkX, chunkZ));
        List<Chunk> mine = HELD_BY_TEST.get(helper.getBounds());
        if (mine != null && mine.remove(chunk)) {
            letGo(chunk);
        }
    }

    /** Lets go of the chunks that the test with these bounds forced, those no other test holds. Called when a test finishes. */
    public static void release(AABB testBounds) {
        List<Chunk> mine = HELD_BY_TEST.remove(testBounds);
        if (mine == null) {
            return;
        }
        mine.forEach(FarChunks::letGo);
    }

    /** Drops one hold on a chunk, and unforces it when no test holds it any more. */
    private static void letGo(Chunk chunk) {
        if (HOLDS.merge(chunk, -1, Integer::sum) == 0) {
            HOLDS.remove(chunk);
            chunk.level().setChunkForced(ChunkPos.getX(chunk.pos()), ChunkPos.getZ(chunk.pos()), false);
        }
    }

    /** What a wait is for, named in the failure message. */
    private enum Awaited {
        ENTITY_TICKING("entity-ticking"),
        UNLOADED("unloaded");

        private final String text;

        Awaited(String text) {
            this.text = text;
        }
    }

    /**
     * The most that one server tick adds to a {@link Deadline#await} wait. A batch of 50 tests starts in one tick, and each test's first tick
     * builds its room and generates its chunks of the tall world on the server thread (1 to 5 s apiece), so a waiter's first tick can last a
     * minute and a deadline of 30 s runs out before the light engine or the sweep of a hold had a second tick (#803: {@code RoomCarverSendTest}
     * took 34 s over 3 ticks). Time that ticks spent past this is other tests' work, and is not counted.
     */
    private static final long MAX_COUNTED_TICK_NANOS = 1_000_000_000L;

    /**
     * A wall-clock limit for one wait. For {@link #await}, which a test calls once for each waiting tick, a tick longer than
     * one second counts as one second: a deadline of N seconds is N seconds of ticks that ran at pace, and a
     * hung wait still fails (after N ticks, at the worst).
     */
    public static final class Deadline {
        private final LongSupplier clock;
        private long endNanos;
        /** When {@link #await} last ran, or the deadline began. Server thread only. */
        private long lastNanos;

        private Deadline(int seconds, LongSupplier clock) {
            this.clock = clock;
            lastNanos = clock.getAsLong();
            endNanos = lastNanos + seconds * 1_000_000_000L;
        }

        /**
         * Call once per tick while waiting for the chunk to unload. Returns whether it has, and otherwise sleeps
         * briefly and fails the test naming the chunk once {@link #WAIT_SECONDS} have passed.
         */
        public boolean awaitUnloaded(GameTestHelper helper, ServerLevel level, int chunkX, int chunkZ) {
            boolean reached = level.getChunkSource().getChunkNow(chunkX, chunkZ) == null;
            await(helper, level, reached, () -> timeout(new ChunkPos(chunkX, chunkZ), level, Awaited.UNLOADED));
            return reached;
        }

        /** Whether the {@link #WAIT_SECONDS} have passed. For a waiter with no {@code GameTestHelper}, such as a client scenario. */
        public boolean expired() {
            return clock.getAsLong() - endNanos > 0;
        }

        private static String timeout(ChunkPos chunk, ServerLevel level, Awaited awaited) {
            return String.format(
                    "chunk %s in %s was not %s after %d s", chunk, level.dimension(), awaited.text, WAIT_SECONDS);
        }

        /**
         * Call once per tick while waiting for any state: pass whether it has settled. When it has not, sleeps briefly, and fails
         * the test with {@code failure} once {@link #WAIT_SECONDS} have passed. The message is built only then.
         */
        public void await(GameTestHelper helper, ServerLevel level, boolean settled, Supplier<String> failure) {
            long now = clock.getAsLong();
            long tickNanos = now - lastNanos;
            lastNanos = now;
            if (settled) {
                return;
            }
            if (tickNanos > MAX_COUNTED_TICK_NANOS) {
                endNanos += tickNanos - MAX_COUNTED_TICK_NANOS;
            }
            if (expired()) {
                throw helper.assertionException(
                        Component.literal(failure.get() + "; held chunks: " + LightSync.heldChunks()));
            }
            // One sleep per server tick across all waiters, so N waiters do not stretch a tick N times.
            int tick = level.getServer().getTickCount();
            if (tick == lastSleptTick) {
                return;
            }
            lastSleptTick = tick;
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Starts the clock for {@link Deadline#awaitUnloaded}. */
    public static Deadline deadline() {
        return deadline(WAIT_SECONDS);
    }

    /** A clock for a wait longer than {@link #WAIT_SECONDS}, such as a world load. */
    public static Deadline deadline(int seconds) {
        return new Deadline(seconds, System::nanoTime);
    }

    /** As {@link #deadline(int)} on the given clock (nanoseconds), so {@link FarChunksTest} can stall a tick without waiting. */
    public static Deadline deadline(int seconds, LongSupplier clock) {
        return new Deadline(seconds, clock);
    }

    /**
     * Runs {@code then} on the first tick when {@code pos} in {@code level} is entity-ticking, or fails the
     * test naming the chunk and dimension after {@link #WAIT_SECONDS} of wall-clock time. {@code then} must
     * not register tick callbacks: register them first and let them wait for what {@code then} sets.
     *
     * @throws IllegalStateException if called after the test's first tick, as {@link TestTicks#each} does
     */
    public static void awaitEntityTicking(GameTestHelper helper, ServerLevel level, BlockPos pos, Runnable then) {
        awaitEntityTicking(helper, level, List.of(pos), index -> then.run());
    }

    /**
     * As the single-position form, for several columns at once with one tick callback. A test that waits for many chunks calls
     * this and not the single form in a loop. {@code then} runs with the index in {@code positions} of a
     * column on the first tick when it is entity-ticking; the failure after {@link #WAIT_SECONDS} names the first column still waiting.
     */
    public static void awaitEntityTicking(
            GameTestHelper helper, ServerLevel level, List<BlockPos> positions, IntConsumer then) {
        if (helper.getTick() != 0) {
            throw new IllegalStateException(
                    "FarChunks.awaitEntityTicking must be called from the test method, not from a tick callback");
        }
        for (BlockPos pos : positions) {
            force(helper, level, pos.getX() >> 4, pos.getZ() >> 4);
        }
        Deadline deadline = deadline();
        boolean[] done = new boolean[positions.size()];
        int[] waiting = {positions.size()};
        TestTicks.each(helper, () -> {
            if (waiting[0] == 0) {
                return;
            }
            int firstWaiting = -1;
            for (int i = 0; i < done.length; i++) {
                if (done[i]) {
                    continue;
                }
                if (level.isPositionEntityTicking(positions.get(i))) {
                    done[i] = true;
                    waiting[0]--;
                    then.accept(i);
                } else if (firstWaiting < 0) {
                    firstWaiting = i;
                }
            }
            if (firstWaiting >= 0) {
                BlockPos pos = positions.get(firstWaiting);
                deadline.await(
                        helper,
                        level,
                        false,
                        () -> Deadline.timeout(
                                new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4), level, Awaited.ENTITY_TICKING));
            }
        });
    }
}
