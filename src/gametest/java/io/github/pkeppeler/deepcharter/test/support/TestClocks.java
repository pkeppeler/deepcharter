package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;

/** The one way a server test writes a world clock; {@code gradle/gametest.gradle} fails the build for a direct write elsewhere. */
public final class TestClocks {
	private TestClocks() {
	}

	/**
	 * Runs {@code body} with {@code clock} paused at {@code ticks}, then puts its ticks and pause back. Call it on the
	 * server thread: the pin and the restore happen on the caller's tick, so no other test sees the pinned clock.
	 */
	public static void paused(MinecraftServer server, Holder<WorldClock> clock, long ticks, Runnable body) {
		ServerClockManager clocks = server.clockManager();
		ClockInstance before = clocks.getInstance(clock);
		long originalTicks = before.totalTicks();
		boolean originalPaused = before.isPaused();
		try {
			clocks.setPaused(clock, true);
			clocks.setTotalTicks(clock, ticks);
			body.run();
		} finally {
			clocks.setTotalTicks(clock, originalTicks);
			clocks.setPaused(clock, originalPaused);
		}
	}
}
