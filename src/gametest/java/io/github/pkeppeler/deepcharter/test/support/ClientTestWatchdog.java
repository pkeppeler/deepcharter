package io.github.pkeppeler.deepcharter.test.support;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wall-clock limit for one client GameTest class (#434). The CI client suite twice hung until its 15-minute job limit, in two
 * classes, with every thread parked (see {@code tests.md}); nothing in the JVM could say which class or where. {@link #arm} starts
 * the clock for a class and drops the previous one, so a class is timed from its start to the start of the next. When the limit
 * passes, the watchdog thread (not a game thread, so a deadlocked game cannot stop it) reports the class and every thread's full
 * stack, then ends the JVM with a failing exit code. It does not interrupt the test thread: a hang like #434's is a deadlock that
 * an interrupt does not break.
 *
 * <p>The clock starts when the client JVM runs the class, so it never counts a wait for the client lock, which Gradle does before
 * the JVM starts. {@link #SECONDS_ENV} sets the default limit, and {@code 0} turns the watchdog off.
 */
public final class ClientTestWatchdog {
	/** Environment variable that replaces {@link #DEFAULT_SECONDS}; {@code 0} disables every limit. */
	public static final String SECONDS_ENV = "DEEPCHARTER_CLIENT_TEST_SECONDS";
	/** Healthy classes take 3 to 40 s on the CI runner (run 38101621063, the slowest TwoPlayerClientTest), so none needs its own limit. */
	public static final int DEFAULT_SECONDS = 180;
	/** Passed as a class's limit to leave it untimed, for a class that is long by design, such as a recording. */
	public static final int NO_LIMIT = -1;
	private static final int EXIT_CODE = 1;
	/** Time for the asynchronous log appenders to write the dump before the JVM is halted. */
	private static final long FLUSH_MILLIS = 3000;
	private static final Logger LOGGER = LoggerFactory.getLogger("ClientTestWatchdog");

	/** What the watchdog found: the class, its limit and every thread's stack. */
	public record Timeout(String test, Duration limit, String threads) {
		public String message() {
			return "CLIENT-GAMETEST TIMEOUT " + test + " ran longer than " + limit.toSeconds() + " s (" + SECONDS_ENV + " sets the default,"
					+ " ClientTestLog.start(this, seconds) sets one class); failing the run instead of waiting out the job. Thread dump:\n" + threads;
		}
	}

	private static final ClientTestWatchdog INSTANCE = new ClientTestWatchdog(ClientTestWatchdog::failTheRun);

	private final Consumer<Timeout> onTimeout;
	private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "Client test watchdog");
		thread.setDaemon(true);
		return thread;
	});
	private ScheduledFuture<?> pending;

	/** A watchdog that calls {@code onTimeout} on its own thread when a limit passes; the shared one halts the JVM. */
	public ClientTestWatchdog(Consumer<Timeout> onTimeout) {
		this.onTimeout = onTimeout;
	}

	/** The watchdog every client test class is armed on, through {@code ClientTestLog.start}. */
	public static ClientTestWatchdog shared() {
		return INSTANCE;
	}

	/**
	 * Starts the clock for {@code test}, replacing the previous class's. {@code seconds} is the class's own limit, or 0 for the
	 * default; {@link #NO_LIMIT} leaves the class untimed. The environment can replace the default or turn every limit off.
	 */
	public void armForClass(String test, int seconds) {
		String env = System.getenv(SECONDS_ENV);
		int fromEnv = env == null || env.isBlank() ? DEFAULT_SECONDS : parseSeconds(env);
		if (fromEnv == 0 || seconds == NO_LIMIT) {
			disarm();
			return;
		}
		arm(test, Duration.ofSeconds(seconds > 0 ? seconds : fromEnv));
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
		throw new IllegalStateException(SECONDS_ENV + " is '" + env + "', expected whole seconds, or 0 to turn the watchdog off");
	}

	/** Starts the clock for {@code test}, replacing any earlier one. */
	public synchronized void arm(String test, Duration limit) {
		disarm();
		pending = clock.schedule(() -> onTimeout.accept(new Timeout(test, limit, dumpThreads())), limit.toMillis(), TimeUnit.MILLISECONDS);
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
