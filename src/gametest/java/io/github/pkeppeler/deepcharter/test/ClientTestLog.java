package io.github.pkeppeler.deepcharter.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkeppeler.deepcharter.test.support.ClientTestWatchdog;

/**
 * Names each client GameTest in the log when it starts, and starts its wall-clock limit ({@link ClientTestWatchdog}). Fabric's
 * client GameTest runner logs nothing between tests, so a stalled CI run (issue 175) could not say which test it stalled in.
 * Every {@code runTest} calls this first.
 */
public final class ClientTestLog {
	private static final Logger LOGGER = LoggerFactory.getLogger("ClientTestLog");

	private ClientTestLog() {
	}

	/** Log one line naming the test class and time it against the limit. */
	public static void start(Object test) {
		String name = test.getClass().getSimpleName();
		LOGGER.info("CLIENT-GAMETEST START {}", name);
		ClientTestWatchdog.shared().armForClass(name);
	}

	/** As {@link #start}, for a class that is long by design, such as a recording: it is not timed. */
	public static void startUntimed(Object test) {
		LOGGER.info("CLIENT-GAMETEST START {}", test.getClass().getSimpleName());
		ClientTestWatchdog.shared().disarm();
	}
}
