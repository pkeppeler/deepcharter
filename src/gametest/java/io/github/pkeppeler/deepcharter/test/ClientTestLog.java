package io.github.pkeppeler.deepcharter.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Names each client GameTest in the log when it starts. Fabric's client GameTest runner logs nothing between tests, so a
 * stalled CI run (issue 175) could not say which test it stalled in. Every {@code runTest} calls this first.
 */
public final class ClientTestLog {
	private static final Logger LOGGER = LoggerFactory.getLogger("ClientTestLog");

	private ClientTestLog() {
	}

	/** Log one line naming the test class. */
	public static void start(Object test) {
		LOGGER.info("CLIENT-GAMETEST START {}", test.getClass().getSimpleName());
	}
}
