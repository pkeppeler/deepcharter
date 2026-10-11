package io.github.pkeppeler.deepcharter.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkeppeler.deepcharter.test.support.ClientTestWatchdog;

/**
 * Names each client GameTest in the log when it starts, and starts its wall-clock limit (#434, {@link ClientTestWatchdog}). Fabric's
 * client GameTest runner logs nothing between tests, so a stalled CI run (issue 175) could not say which test it stalled in.
 * Every {@code runTest} calls this first.
 */
public final class ClientTestLog {
	private static final Logger LOGGER = LoggerFactory.getLogger("ClientTestLog");

	private ClientTestLog() {
	}

	/** Log one line naming the test class and time it against the default limit. */
	public static void start(Object test) {
		start(test, 0);
	}

	/**
	 * As {@link #start(Object)}, with this class's own limit in seconds, for a class that is slow by design: say why next to the call.
	 * {@link ClientTestWatchdog#NO_LIMIT} leaves the class untimed.
	 */
	public static void start(Object test, int seconds) {
		String name = test.getClass().getSimpleName();
		LOGGER.info("CLIENT-GAMETEST START {}", name);
		ClientTestWatchdog.shared().armForClass(name, seconds);
	}
}
