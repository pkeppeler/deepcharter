package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Awaits entity ticking in a far chunk of a fresh world. The test method must call this, never a tick
 * callback. It forces the chunk, because a mock player teleported within its own dimension never loads
 * chunks around itself. A forced chunk stays forced as long as the test world does.
 */
public final class FarChunks {
	/** Wall-clock seconds that a wait for a chunk may take. The GameTest server ticks unthrottled, so a tick budget alone is only seconds of real time. */
	public static final int WAIT_SECONDS = 120;
	/** Milliseconds slept per waiting tick, so the chunk generation threads get CPU. */
	static final int POLL_MILLIS = 2;
	/**
	 * A test's {@code maxTicks} for one wait. Each waiting tick takes at least {@link #POLL_MILLIS}, so no more than
	 * this many ticks pass in {@link #WAIT_SECONDS}, and the wall-clock failure always fires before the GameTest limit.
	 * Add the ticks of the test's own work. A test with a second wait adds this once more.
	 */
	public static final int AWAIT_BUDGET_TICKS = WAIT_SECONDS * 1000 / POLL_MILLIS + 600;

	/** The server tick on which a waiter last slept. Server thread only. */
	private static int lastSleptTick = -1;

	private FarChunks() {
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

	/** A wall-clock limit for one wait. */
	public static final class Deadline {
		private final long endNanos;

		private Deadline(int seconds) {
			endNanos = System.nanoTime() + seconds * 1_000_000_000L;
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
			return System.nanoTime() - endNanos > 0;
		}

		private static String timeout(ChunkPos chunk, ServerLevel level, Awaited awaited) {
			return String.format("chunk %s in %s was not %s after %d s", chunk, level.dimension(), awaited.text, WAIT_SECONDS);
		}

		/**
		 * Call once per tick while waiting for any state: pass whether it has settled. When it has not, sleeps briefly, and fails
		 * the test with {@code failure} once {@link #WAIT_SECONDS} have passed. The message is built only then.
		 */
		public void await(GameTestHelper helper, ServerLevel level, boolean settled, Supplier<String> failure) {
			if (settled) {
				return;
			}
			if (expired()) {
				throw helper.assertionException(Component.literal(failure.get()));
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
		return new Deadline(WAIT_SECONDS);
	}

	/** Starts a clock for a wait that needs longer than {@link #WAIT_SECONDS}, such as a world load. Such a wait uses only {@code expired()}. */
	public static Deadline deadline(int seconds) {
		return new Deadline(seconds);
	}

	/**
	 * Runs {@code then} on the first tick when {@code pos} in {@code level} is entity-ticking, or fails the
	 * test naming the chunk and dimension after {@link #WAIT_SECONDS} of wall-clock time. {@code then} must
	 * not register tick callbacks: register them first and let them wait for what {@code then} sets.
	 *
	 * @throws IllegalStateException if called after the test's first tick, because registering a tick callback
	 *         there crashes vanilla's GameTest loop with an unrelated NullPointerException
	 */
	public static void awaitEntityTicking(GameTestHelper helper, ServerLevel level, BlockPos pos, Runnable then) {
		awaitEntityTicking(helper, level, List.of(pos), index -> then.run());
	}

	/**
	 * As the single-position form, for several columns at once with one tick callback. A test that waits for many chunks calls
	 * this and not the single form in a loop: vanilla registers a callback for every tick of the test's {@code maxTicks}, and
	 * each registration makes every tick of a long test slower. {@code then} runs with the index in {@code positions} of a
	 * column on the first tick when it is entity-ticking; the failure after {@link #WAIT_SECONDS} names the first column still waiting.
	 */
	public static void awaitEntityTicking(GameTestHelper helper, ServerLevel level, List<BlockPos> positions, IntConsumer then) {
		if (helper.getTick() != 0) {
			throw new IllegalStateException("FarChunks.awaitEntityTicking must be called from the test method, not from a tick callback");
		}
		for (BlockPos pos : positions) {
			level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
		}
		Deadline deadline = deadline();
		boolean[] done = new boolean[positions.size()];
		int[] waiting = {positions.size()};
		helper.onEachTick(() -> {
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
				deadline.await(helper, level, false, () -> Deadline.timeout(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4), level, Awaited.ENTITY_TICKING));
			}
		});
	}
}
