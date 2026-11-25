package io.github.pkeppeler.deepcharter.test.support;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * The longest server tick since a test asked, in wall nanoseconds, for the tests that measure a stall (#1316): a
 * crossing that loads chunks on the server thread shows as one long tick. The recording is process-wide, so a test
 * reads it as "at least this long", and logs it without asserting a bound, which would flake on a loaded machine.
 * Server thread only.
 */
public final class TickTimes {
    private static long startedNanos;
    private static long peakNanos;
    private static boolean installed;

    private TickTimes() {}

    /** Starts a new peak; the first call hooks the server tick. */
    public static void restart() {
        if (!installed) {
            installed = true;
            ServerTickEvents.START_SERVER_TICK.register(server -> startedNanos = System.nanoTime());
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                // The tick that installed the hook has no start.
                if (startedNanos != 0) {
                    peakNanos = Math.max(peakNanos, System.nanoTime() - startedNanos);
                }
            });
        }
        peakNanos = 0;
    }

    /** The longest tick, in milliseconds, since {@link #restart}. */
    public static double peakMillis() {
        return peakNanos / 1_000_000.0;
    }
}
