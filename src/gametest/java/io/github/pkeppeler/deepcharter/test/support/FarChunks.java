package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.LightSync;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

/**
 * Awaits entity ticking in a far chunk of a fresh world. The test method must call this, never a tick
 * callback. It forces the chunk, without loading it on the server thread (#1016), because a mock player teleported within its own dimension never loads
 * chunks around itself. A chunk it forces is released when the test finishes ({@link #release}): a forced chunk pins the
 * generation neighbourhood around it (up to 17 x 17 chunks of proto chunks and their sections), so one left forced for the rest
 * of the run grows the heap with the suite (#569).
 */
public final class FarChunks {
    /** Wall-clock seconds that a wait for a chunk may take. The GameTest server ticks unthrottled, so a tick budget alone is only seconds of real time. */
    public static final int WAIT_SECONDS = 120;
    /** Waited ticks before a wall-clock limit fails a wait: a batch that blocks the server thread ticks a few times a minute (#1002). */
    public static final int MIN_WAITED_TICKS = 1_200;
    /** A wait fails once the wall clock passes this many times its limit, however few ticks it has waited, so a stuck wait still ends with its message. */
    public static final int CEILING_FACTOR = 5;
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
        // chunks are pinned in every world, and a client run makes a server for each test class: 6 levels and 70 MB of
        // each world stayed on the heap until it ran out (#803).
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
        // setChunkForced would also load the chunk on the server thread; the ticket alone loads it on the workers.
        boolean forcedNow = level.getChunkSource().updateChunkForced(new ChunkPos(chunkX, chunkZ), true);
        if (forcedNow || HOLDS.containsKey(chunk)) {
            HOLDS.merge(chunk, 1, Integer::sum);
            mine.add(chunk);
        }
    }

    /**
     * Forces a chunk for the rest of the world, since the runner unforces every forced chunk at each batch end (#669).
     * It loads the chunk on the server thread, once, when the colony is built. A ticket alone, as {@link #force} takes,
     * made {@code ColonyTest} find the pad gone on the first tick of a full run now and then (#1020), so the pad's load
     * stays synchronous.
     */
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
     * A wall-clock limit for one wait. The {@code await} methods fail a wait only past the limit and {@link #MIN_WAITED_TICKS} waited
     * ticks, or past {@value #CEILING_FACTOR} times the limit; {@link #expired()} is the bare wall clock and applies neither.
     */
    public static final class Deadline {
        private final long startNanos;
        private final long budgetNanos;
        /** The server tick of the first {@link #await}, or -1. Server thread only. */
        private int firstAwaitTick = -1;

        private Deadline(int seconds) {
            startNanos = System.nanoTime();
            budgetNanos = seconds * 1_000_000_000L;
        }

        /**
         * Whether a wait fails: its wall clock is past the limit and it has waited {@link #MIN_WAITED_TICKS} ticks, or the clock is past
         * {@value #CEILING_FACTOR} times the limit.
         */
        public static boolean failed(long elapsedNanos, long budgetNanos, int waitedTicks) {
            return elapsedNanos > budgetNanos
                    && (waitedTicks >= MIN_WAITED_TICKS || elapsedNanos > CEILING_FACTOR * budgetNanos);
        }

        /**
         * Call once per tick while waiting for the chunk to unload. Returns whether it has, and otherwise sleeps
         * briefly and fails the test naming the chunk as {@link #await} does.
         */
        public boolean awaitUnloaded(GameTestHelper helper, ServerLevel level, int chunkX, int chunkZ) {
            boolean reached = level.getChunkSource().getChunkNow(chunkX, chunkZ) == null;
            await(helper, level, reached, () -> timeout(new ChunkPos(chunkX, chunkZ), level, Awaited.UNLOADED));
            return reached;
        }

        /** Whether the limit has passed on the wall clock alone. For a waiter with no {@code GameTestHelper}, such as a client scenario. */
        public boolean expired() {
            return System.nanoTime() - startNanos > budgetNanos;
        }

        private static String timeout(ChunkPos chunk, ServerLevel level, Awaited awaited) {
            return String.format(
                    "chunk %s in %s was not %s after %d s and at least %d ticks (hard ceiling %d s)",
                    chunk,
                    level.dimension(),
                    awaited.text,
                    WAIT_SECONDS,
                    MIN_WAITED_TICKS,
                    CEILING_FACTOR * WAIT_SECONDS);
        }

        /**
         * Call once per tick while waiting for any state: pass whether it has settled. When it has not, sleeps briefly, and fails
         * the test with {@code failure} (and the ticks and seconds waited) once the limit has passed and {@link #MIN_WAITED_TICKS}
         * ticks have waited, or {@value #CEILING_FACTOR} times the limit has (see {@link #failed}). The message is built only then.
         */
        public void await(GameTestHelper helper, ServerLevel level, boolean settled, Supplier<String> failure) {
            if (settled) {
                return;
            }
            int tick = level.getServer().getTickCount();
            if (firstAwaitTick < 0) {
                firstAwaitTick = tick;
            }
            long elapsed = System.nanoTime() - startNanos;
            int waited = tick - firstAwaitTick;
            if (failed(elapsed, budgetNanos, waited)) {
                throw helper.assertionException(Component.literal(failure.get() + " after " + elapsed / 1_000_000_000L
                        + " s and " + waited + " ticks; held chunks: " + LightSync.heldChunks()));
            }
            // One sleep per server tick across all waiters, so N waiters do not stretch a tick N times.
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
        return new Deadline(WAIT_SECONDS);
    }

    /** A clock with its own limit: longer than {@link #WAIT_SECONDS} for a world load, shorter for a wait that must fail before a class limit. */
    public static Deadline deadline(int seconds) {
        return new Deadline(seconds);
    }

    /**
     * Runs {@code then} on the first tick when {@code pos} in {@code level} is entity-ticking, or fails the
     * test naming the chunk and dimension as {@link Deadline#await} does. {@code then} must
     * not register tick callbacks: register them first and let them wait for what {@code then} sets.
     *
     * @throws IllegalStateException if called after the test's first tick, as {@link TestTicks#each} does
     */
    public static void awaitEntityTicking(GameTestHelper helper, ServerLevel level, BlockPos pos, Runnable then) {
        awaitEntityTicking(helper, level, List.of(pos), index -> then.run());
    }

    /** A chunk of a level, for {@link #whenBlocksLoaded} and {@link #whenReachedStatus}. */
    public record At(ServerLevel level, int chunkX, int chunkZ) {}

    /**
     * Every chunk from {@code (minChunkX, minChunkZ)} to {@code (maxChunkX, maxChunkZ)}, both inclusive, for
     * {@link #whenBlocksLoaded}.
     */
    public static List<At> rect(ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        List<At> chunks = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new At(level, chunkX, chunkZ));
            }
        }
        return chunks;
    }

    /**
     * Every chunk over the block columns {@code minX..maxX}, {@code minZ..maxZ}, both inclusive, for
     * {@link #whenBlocksLoaded}.
     */
    public static List<At> overBlocks(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        return rect(level, minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4);
    }

    /**
     * Forces every chunk of {@code chunks} (held until the test finishes, or until {@link #release}) and runs {@code then} on the
     * first tick when all of them are loaded, or fails the test naming the first one still missing after {@link #WAIT_SECONDS}. The
     * chunks load on the worker threads, so the server keeps ticking: use this where a test would read or fill a chunk with
     * {@code getChunk}, which loads it on the server thread and starves every test beside it. As {@link #awaitEntityTicking}, call it from
     * the test method, and {@code then} only sets state.
     *
     * @throws IllegalStateException if called after the test's first tick
     */
    private static void awaitLoaded(GameTestHelper helper, List<At> chunks, Runnable then) {
        if (helper.getTick() != 0) {
            throw new IllegalStateException(
                    "FarChunks.awaitLoaded must be called from the test method, not from a tick callback");
        }
        chunks.forEach(chunk -> force(helper, chunk.level(), chunk.chunkX(), chunk.chunkZ()));
        Deadline deadline = deadline();
        boolean[] done = {false};
        TestTicks.each(helper, () -> {
            if (done[0]) {
                return;
            }
            At missing = null;
            for (At chunk : chunks) {
                if (chunk.level().getChunkSource().getChunkNow(chunk.chunkX(), chunk.chunkZ()) == null) {
                    missing = chunk;
                    break;
                }
            }
            if (missing == null) {
                done[0] = true;
                then.run();
                return;
            }
            At first = missing;
            deadline.await(
                    helper,
                    first.level(),
                    false,
                    () -> "chunk " + new ChunkPos(first.chunkX(), first.chunkZ()) + " in "
                            + first.level().dimension() + " was not loaded after " + WAIT_SECONDS + " s");
        });
    }

    /**
     * As {@code awaitLoaded} (which waits for the chunks to load), then runs {@code body} as a step of the test, where
     * it may read the chunks, fail and {@code helper.succeed()}. Call it from the test method, last or with the test's
     * other sequences started after it. Loaded is not entity ticking: an entity that spawns or arrives in these chunks
     * is invisible to {@code getEntity} until it ticks, so for entities use {@code awaitAllEntityTicking} (#1151).
     */
    public static void whenBlocksLoaded(GameTestHelper helper, List<At> chunks, Runnable body) {
        boolean[] loaded = {false};
        awaitLoaded(helper, chunks, () -> loaded[0] = true);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!loaded[0]) {
                        throw helper.assertionException("the chunks are not loaded yet");
                    }
                })
                .thenExecute(body);
    }

    /**
     * Runs {@code body} as a step of the test once every chunk of {@code chunks} has reached {@code status}, with the
     * chunks in the order of the list, or fails the test naming the first one still missing after {@link
     * #WAIT_SECONDS}. For a test that reads blocks and needs no more than a status short of full (a tall world's full
     * chunks cost seconds each, #1142): unlike {@link #whenBlocksLoaded} it does not force the chunks, so they stop at
     * {@code status}. Each waiting tick asks for them with a ticket at that level, which expires on its own, and for
     * generation to that status, so the workers generate them and the server keeps ticking. A chunk that has reached
     * {@code status} is kept by reference, not held. Call it from the test method.
     *
     * @throws IllegalStateException if called after the test's first tick
     */
    public static void whenReachedStatus(
            GameTestHelper helper, List<At> chunks, ChunkStatus status, Consumer<List<ChunkAccess>> body) {
        if (helper.getTick() != 0) {
            throw new IllegalStateException(
                    "FarChunks.whenReachedStatus must be called from the test method, not from a tick callback");
        }
        List<ChunkAccess> reached = new ArrayList<>(Collections.nCopies(chunks.size(), null));
        boolean[] done = {false};
        Deadline deadline = deadline();
        TestTicks.each(helper, () -> {
            if (done[0]) {
                return;
            }
            At missing = null;
            for (int i = 0; i < chunks.size(); i++) {
                if (reached.get(i) != null) {
                    continue;
                }
                At chunk = chunks.get(i);
                ChunkPos pos = new ChunkPos(chunk.chunkX(), chunk.chunkZ());
                ServerChunkCache cache = chunk.level().getChunkSource();
                cache.addTicket(new Ticket(TicketType.UNKNOWN, ChunkLevel.byStatus(status)), pos);
                ChunkHolder holder = cache.chunkMap.getUpdatingChunkIfPresent(pos.pack());
                ChunkAccess access = holder == null ? null : holder.getChunkIfPresent(status);
                if (holder != null && access == null) {
                    // A ticket alone only makes the holder. Asking for the status starts the work on the workers,
                    // and is the same future on every later tick.
                    var _ = holder.scheduleChunkGenerationTask(status, cache.chunkMap);
                }
                if (access != null) {
                    reached.set(i, access);
                } else if (missing == null) {
                    missing = chunk;
                }
            }
            if (missing == null) {
                done[0] = true;
                return;
            }
            At first = missing;
            deadline.await(
                    helper,
                    first.level(),
                    false,
                    () -> "chunk " + new ChunkPos(first.chunkX(), first.chunkZ()) + " in "
                            + first.level().dimension() + " did not reach " + status + " after " + WAIT_SECONDS + " s");
        });
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!done[0]) {
                        throw helper.assertionException("the chunks have not reached " + status + " yet");
                    }
                })
                .thenExecute(() -> body.accept(reached));
    }

    /** As the list form, for a test that needs every column ticking: runs {@code then} once on the tick when the last one is. */
    public static void awaitAllEntityTicking(
            GameTestHelper helper, ServerLevel level, List<BlockPos> positions, Runnable then) {
        int[] waiting = {positions.size()};
        awaitEntityTicking(helper, level, positions, index -> {
            if (--waiting[0] == 0) {
                then.run();
            }
        });
    }

    /**
     * As the single-position form, for several columns at once with one tick callback. A test that waits for many chunks calls
     * this and not the single form in a loop. {@code then} runs with the index in {@code positions} of a
     * column on the first tick when it is entity-ticking; the failure, as {@link Deadline#await} fails, names the first column still waiting.
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
