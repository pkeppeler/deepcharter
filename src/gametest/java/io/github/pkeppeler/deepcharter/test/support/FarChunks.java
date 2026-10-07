package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Awaits entity ticking in a far chunk of a fresh world. The test method must call this, never a tick
 * callback. It forces the chunk, because a mock player teleported within its own dimension never loads
 * chunks around itself. A forced chunk stays forced as long as the test world does.
 */
public final class FarChunks {
	/** Server ticks that {@link #awaitEntityTicking} waits. Add this to a test's own {@code maxTicks}. */
	public static final int AWAIT_BUDGET_TICKS = 12000;

	private FarChunks() {
	}

	/**
	 * Runs {@code then} on the first tick when {@code pos} in {@code level} is entity-ticking, or fails the
	 * test with the position and dimension after {@link #AWAIT_BUDGET_TICKS}. {@code then} must not register
	 * tick callbacks: register them first and let them wait for what {@code then} sets.
	 *
	 * @throws IllegalStateException if called after the test's first tick, because registering a tick callback
	 *         there crashes vanilla's GameTest loop with an unrelated NullPointerException
	 */
	public static void awaitEntityTicking(GameTestHelper helper, ServerLevel level, BlockPos pos, Runnable then) {
		if (helper.getTick() != 0) {
			throw new IllegalStateException("FarChunks.awaitEntityTicking must be called from the test method, not from a tick callback");
		}
		level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
		boolean[] done = {false};
		helper.onEachTick(() -> {
			if (done[0]) {
				return;
			}
			if (level.isPositionEntityTicking(pos)) {
				done[0] = true;
				then.run();
			} else if (helper.getTick() > AWAIT_BUDGET_TICKS) {
				throw helper.assertionException(Component.literal(String.format(
						"%s in %s was not entity-ticking after %d ticks", pos.toShortString(), level.dimension(), AWAIT_BUDGET_TICKS)));
			}
		});
	}
}
