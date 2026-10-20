package io.github.pkeppeler.deepcharter.test.support;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wall-clock limit for one client GameTest class (#434). {@link #arm} starts the clock for a class and drops the previous one. When
 * the limit passes, the watchdog thread (not a game thread, so a deadlocked game cannot stop it) reports the class and every
 * thread's full stack, then halts the JVM with a failing exit code. It does not interrupt the test thread: a deadlock does not
 * break on an interrupt.
 */
public final class ClientTestWatchdog {
    /** Environment variable that replaces {@link #DEFAULT_SECONDS}; {@code 0} disables the limit. */
    public static final String SECONDS_ENV = "DEEPCHARTER_CLIENT_TEST_SECONDS";
    /** Healthy classes take 3 to 40 s on the CI runner (run 38101621063, the slowest TwoPlayerClientTest). */
    public static final int DEFAULT_SECONDS = 180;

    private static final int EXIT_CODE = 1;
    /** Time for the asynchronous log appenders to write the dump before the JVM is halted. */
    private static final long FLUSH_MILLIS = 3000;

    private static final Logger LOGGER = LoggerFactory.getLogger("ClientTestWatchdog");

    /** What the watchdog found: the class, its limit and every thread's stack. */
    public record Timeout(String test, Duration limit, String threads) {
        public String message() {
            return "CLIENT-GAMETEST TIMEOUT " + test + " ran longer than " + limit.toSeconds() + " s (" + SECONDS_ENV
                    + " sets the limit); failing the run instead of waiting out the job. Thread dump:\n" + threads;
        }
    }

    private static final ClientTestWatchdog INSTANCE = new ClientTestWatchdog(ClientTestWatchdog::failTheRun);

    private final Consumer<Timeout> onTimeout;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Client test watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private @Nullable ScheduledFuture<?> pending;

    /** Calls {@code onTimeout} on the watchdog's thread when a limit passes; the shared one halts the JVM. */
    public ClientTestWatchdog(Consumer<Timeout> onTimeout) {
        this.onTimeout = onTimeout;
    }

    /** The watchdog {@code ClientTestLog.start} arms. */
    public static ClientTestWatchdog shared() {
        return INSTANCE;
    }

    /** Starts the clock for {@code test} at the default or {@link #SECONDS_ENV} limit; {@code 0} there leaves it untimed. */
    public void armForClass(String test) {
        String env = System.getenv(SECONDS_ENV);
        int seconds = env == null || env.isBlank() ? DEFAULT_SECONDS : parseSeconds(env);
        if (seconds == 0) {
            disarm();
            return;
        }
        arm(test, Duration.ofSeconds(seconds));
    }

    private static int parseSeconds(String env) {
        try {
            int seconds = Integer.parseInt(env.trim());
            if (seconds >= 0) {
                return seconds;
            }
        } catch (NumberFormatException notANumber) {
            // falls through to the failure below
        }
        throw new IllegalStateException(
                SECONDS_ENV + " is '" + env + "', expected whole seconds, or 0 to turn the watchdog off");
    }

    /** Starts the clock for {@code test}, replacing any earlier one. */
    public synchronized void arm(String test, Duration limit) {
        disarm();
        pending = clock.schedule(
                () -> onTimeout.accept(new Timeout(test, limit, dumpThreads())),
                limit.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** Stops the running clock, if any. */
    public synchronized void disarm() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    /** Every live thread with its full stack and what it waits on; the JDK's {@code ThreadInfo.toString} cuts a stack to 8 frames. */
    static String dumpThreads() {
        StringBuilder out = new StringBuilder();
        for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            out.append('"').append(info.getThreadName()).append("\" ").append(info.getThreadState());
            if (info.getLockName() != null) {
                out.append(" on ").append(info.getLockName());
            }
            if (info.getLockOwnerName() != null) {
                out.append(" owned by \"").append(info.getLockOwnerName()).append('"');
            }
            out.append('\n');
            for (StackTraceElement frame : info.getStackTrace()) {
                out.append("\tat ").append(frame).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void failTheRun(Timeout timeout) {
        LOGGER.error(timeout.message());
        try {
            Thread.sleep(FLUSH_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        // halt, not exit: shutdown hooks and the game's own close would wait on the same threads that are stuck
        Runtime.getRuntime().halt(EXIT_CODE);
    }
}
