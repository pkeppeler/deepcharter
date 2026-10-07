package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * A GameTest world is fresh, so a chunk far from the origin takes real time to generate and to
 * start ticking entities. An entity spawned there does not tick until then, so a fixed tick
 * budget counted from the spawn is unreliable. Await entity ticking before you drive an entity
 * in a far chunk. Something must keep the chunk loaded (a player at the position, or a ticket).
 */
public final class FarChunks {
	/** Server ticks that {@link #awaitEntityTicking} waits. Add this to a test's own {@code maxTicks}. */
	public static final int AWAIT_BUDGET_TICKS = 12000;

	private FarChunks() {
	}

	/**
	 * Runs {@code then} on the first tick when {@code pos} in {@code level} is entity-ticking. Fails
	 * the test, naming the position and dimension, if that takes more than {@link #AWAIT_BUDGET_TICKS}.
	 * Call this from the test method, not from a callback. {@code then} runs inside the GameTest tick
	 * loop, so it can spawn and move entities but must not call {@code onEachTick}, {@code succeedWhen}
	 * or {@code runAfterDelay} (vanilla then modifies its callback map while iterating it and crashes).
	 * Register those first and let them wait for what {@code then} sets.
	 */
	public static void awaitEntityTicking(GameTestHelper helper, ServerLevel level, BlockPos pos, Runnable then) {
		long deadline = helper.getTick() + AWAIT_BUDGET_TICKS;
		boolean[] done = {false};
		helper.onEachTick(() -> {
			if (done[0]) {
				return;
			}
			if (level.isPositionEntityTicking(pos)) {
				done[0] = true;
				then.run();
			} else if (helper.getTick() > deadline) {
				done[0] = true;
				throw helper.assertionException(Component.literal(String.format(
						"%s in %s was not entity-ticking after %d ticks", pos.toShortString(), level.dimension(), AWAIT_BUDGET_TICKS)));
			}
		});
	}
}
