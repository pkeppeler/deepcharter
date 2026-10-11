package io.github.pkeppeler.deepcharter.test;

import java.util.concurrent.CountDownLatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import io.github.pkeppeler.deepcharter.test.support.ClientTestWatchdog;

/**
 * Fixture for #434: a client test class that hangs for ever, to show {@link ClientTestWatchdog} fails the run with a thread dump
 * and this class's name. Off by default: it does nothing unless {@value #FLAG} is {@code 1}. To see it fail fast:
 * {@code DEEPCHARTER_HANG_FIXTURE=1 DEEPCHARTER_CLIENT_TEST_SECONDS=20 ./gradlew runClientGameTest -PclientTests=WatchdogHangClientTest}.
 */
public class WatchdogHangClientTest implements FabricClientGameTest {
	static final String FLAG = "DEEPCHARTER_HANG_FIXTURE";

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		if (!"1".equals(System.getenv(FLAG))) {
			return;
		}
		try {
			new CountDownLatch(1).await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("The hanging fixture was interrupted", e);
		}
	}
}
