package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.client.pod.PodChase;
import io.github.pkeppeler.deepcharter.test.support.ClientTestWatchdog;
import io.github.pkeppeler.deepcharter.test.support.HeapHistogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Names each client GameTest in the log when it starts, starts its wall-clock limit ({@link ClientTestWatchdog}) and, when asked, records the
 * heap ({@link HeapHistogram}). Fabric's
 * client GameTest runner logs nothing between tests, so a stalled CI run (issue 175) could not say which test it stalled in.
 * Every {@code runTest} calls this first. It also turns the pilot's chase camera ({@code PodChase}) off, because a test
 * that stages a first-person cockpit keeps the camera it sets; the tests of the chase camera turn it back on.
 */
public final class ClientTestLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("ClientTestLog");

    private ClientTestLog() {}

    /** Log one line naming the test class and time it against the limit. */
    public static void start(Object test) {
        PodChase.setAutomatic(false);
        String name = test.getClass().getSimpleName();
        LOGGER.info("CLIENT-GAMETEST START {}", name);
        HeapHistogram.record(name);
        ClientTestWatchdog.shared().armForClass(name);
    }

    /** As {@link #start}, for a class that is long by design, such as a recording: it is not timed. */
    public static void startUntimed(Object test) {
        PodChase.setAutomatic(false);
        String name = test.getClass().getSimpleName();
        LOGGER.info("CLIENT-GAMETEST START {}", name);
        HeapHistogram.record(name);
        ClientTestWatchdog.shared().disarm();
    }
}
