package io.github.pkeppeler.deepcharter.test.support;

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
	private static final int POLL_MILLIS = 2;
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
		private final long endNanos = System.nanoTime() + WAIT_SECONDS * 1_000_000_000L;

		private Deadline() {
		}

		/**
		 * Call once per tick while waiting for the chunk to unload. Returns whether it has, and otherwise sleeps
		 * briefly and fails the test naming the chunk once {@link #WAIT_SECONDS} have passed.
		 */
		public boolean awaitUnloaded(GameTestHelper helper, ServerLevel level, int chunkX, int chunkZ) {
			boolean reached = level.getChunkSource().getChunkNow(chunkX, chunkZ) == null;
			pause(helper, level, new ChunkPos(chunkX, chunkZ), Awaited.UNLOADED, reached);
			return reached;
		}

		private void pause(GameTestHelper helper, ServerLevel level, ChunkPos chunk, Awaited awaited, boolean reached) {
			if (reached) {
				return;
			}
			if (System.nanoTime() - endNanos > 0) {
				throw helper.assertionException(Component.literal(String.format("chunk %s in %s was not %s after %d s",
						chunk, level.dimension(), awaited.text, WAIT_SECONDS)));
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
		return new Deadline();
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
		if (helper.getTick() != 0) {
			throw new IllegalStateException("FarChunks.awaitEntityTicking must be called from the test method, not from a tick callback");
		}
		ChunkPos chunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
		level.setChunkForced(chunk.x(), chunk.z(), true);
		Deadline deadline = deadline();
		boolean[] done = {false};
		helper.onEachTick(() -> {
			if (done[0]) {
				return;
			}
			boolean reached = level.isPositionEntityTicking(pos);
			deadline.pause(helper, level, chunk, Awaited.ENTITY_TICKING, reached);
			if (reached) {
				done[0] = true;
				then.run();
			}
		});
	}
}
