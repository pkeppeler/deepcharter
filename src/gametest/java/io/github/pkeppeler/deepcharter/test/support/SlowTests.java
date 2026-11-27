package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.gametest.framework.GameTestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Names the tests that hold the server thread, which the runner does not: "Can't keep up" says how far behind the server is, not which
 * test made it so. {@code GameTestInfoMixin} reports every test tick over {@link #SLOW_TICK_MS} and every test that took over
 * {@link #SLOW_TEST_SECONDS} of wall time, and a test that waits on a wall-clock deadline in the same batch is its victim.
 */
public final class SlowTests {
    private static final Logger LOGGER = LoggerFactory.getLogger(SlowTests.class);
    private static final long SLOW_TICK_MS = 1000;
    private static final long SLOW_TEST_SECONDS = 30;

    private SlowTests() {}

    /** One test's share of a server tick, in nanoseconds. */
    public static void tick(GameTestInfo info, long nanos) {
        long millis = nanos / 1_000_000;
        if (millis >= SLOW_TICK_MS) {
            LOGGER.warn(
                    "[slow-test] {} held the server thread {} ms in its tick {}", info.id(), millis, info.getTick());
        }
    }

    /** A finished test's wall time since it started. */
    public static void finished(GameTestInfo info) {
        long seconds = info.getRunTime() / 1000;
        if (seconds >= SLOW_TEST_SECONDS) {
            LOGGER.warn("[slow-test] {} took {} s of wall time over {} ticks", info.id(), seconds, info.getTick());
        }
    }
}
