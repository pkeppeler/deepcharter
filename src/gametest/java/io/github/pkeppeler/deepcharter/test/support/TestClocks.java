package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;

/**
 * The one way a server test writes a world clock. A clock is shared by every test in the world, so this captures the clock's ticks
 * and pause, pins the phase and runs the body in a {@code try}, and puts the captured state back in the {@code finally}, all on
 * the caller's tick. Call it on the server thread; the pin is invisible to other tests only because the body runs inside the caller's tick.
 * {@code gradle/gametest.gradle} fails the build for a direct clock write elsewhere in a server test.
 */
public final class TestClocks {
	private TestClocks() {
	}

	/** Runs {@code body} with {@code clock} paused at {@code ticks}, then puts its ticks and pause back. */
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
