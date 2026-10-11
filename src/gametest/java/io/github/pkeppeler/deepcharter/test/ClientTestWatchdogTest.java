package io.github.pkeppeler.deepcharter.test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;

import io.github.pkeppeler.deepcharter.test.support.ClientTestWatchdog;
import io.github.pkeppeler.deepcharter.test.support.ClientTestWatchdog.Timeout;

/** Server GameTests for {@link ClientTestWatchdog} (#434), with a handler that records the report instead of halting the JVM. */
public class ClientTestWatchdogTest {
	@GameTest
	public void aClassOverItsLimitIsReportedWithTheThreadsThatAreStuck(GameTestHelper helper) throws InterruptedException {
		CountDownLatch never = new CountDownLatch(1);
		Thread stuck = new Thread(() -> {
			try {
				never.await();
			} catch (InterruptedException released) {
				// the test releases it
			}
		}, "watchdog-test-stuck-thread");
		stuck.setDaemon(true);
		stuck.start();
		SynchronousQueue<Timeout> reports = new SynchronousQueue<>();
		ClientTestWatchdog watchdog = new ClientTestWatchdog(timeout -> reports.offer(timeout));
		try {
			watchdog.arm("HungClientTest", Duration.ofMillis(300));
			Timeout timeout = reports.poll(10, TimeUnit.SECONDS);
			if (timeout == null) {
				throw helper.assertionException("the watchdog did not fire within 10 s of a 300 ms limit");
			}
			String message = timeout.message();
			if (!timeout.test().equals("HungClientTest") || !message.contains("HungClientTest ran longer than")) {
				throw helper.assertionException("the report does not name the class and its limit: " + message);
			}
			if (!message.contains("watchdog-test-stuck-thread") || !message.contains("CountDownLatch")) {
				throw helper.assertionException("the dump has no stack of the stuck thread: " + message);
			}
		} finally {
			stuck.interrupt();
			watchdog.disarm();
		}
		helper.succeed();
	}

	@GameTest
	public void armingTheNextClassOrDisarmingStopsTheClock(GameTestHelper helper) throws InterruptedException {
		SynchronousQueue<Timeout> reports = new SynchronousQueue<>();
		ClientTestWatchdog watchdog = new ClientTestWatchdog(timeout -> reports.offer(timeout));
		try {
			watchdog.arm("FirstClientTest", Duration.ofMillis(300));
			watchdog.arm("SecondClientTest", Duration.ofHours(1));
			Timeout early = reports.poll(1, TimeUnit.SECONDS);
			if (early != null) {
				throw helper.assertionException("the first class's clock ran on after the second class started: " + early.test());
			}
			watchdog.arm("ThirdClientTest", Duration.ofMillis(300));
			watchdog.disarm();
			Timeout disarmed = reports.poll(1, TimeUnit.SECONDS);
			if (disarmed != null) {
				throw helper.assertionException("a disarmed clock still fired for " + disarmed.test());
			}
		} finally {
			watchdog.disarm();
		}
		helper.succeed();
	}
}
