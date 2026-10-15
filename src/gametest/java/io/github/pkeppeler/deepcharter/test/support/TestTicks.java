package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/** Per-tick test bodies that do not pile up in the test's schedule (#548). */
public final class TestTicks {
    private TestTicks() {}

    /**
     * Runs {@code body} on every tick from the test method's own tick until the test finishes; an exception it throws fails the test.
     * It keeps one schedule entry where {@code GameTestHelper.onEachTick} queued one per remaining tick. A scheduled runnable
     * cannot reschedule itself (the runner removes it while iterating), so a sequence event does the put for the next tick.
     *
     * @throws IllegalStateException if called after the test's first tick: a put from a scheduled runnable corrupts the runner's
     *         iteration and one from a sequence event throws a ConcurrentModificationException
     */
    public static void each(GameTestHelper helper, Runnable body) {
        if (helper.getTick() != 0) {
            throw new IllegalStateException(
                    "TestTicks.each must be called from the test method, not from a tick callback");
        }
        helper.runAtTickTime(0, body);
        helper.startSequence().thenWaitUntil(() -> {
            helper.runAtTickTime(helper.getTick() + 1, body);
            // Never completes, so the next tick has this event again. Past the timeout the runner fails the test with
            // this message.
            throw new GameTestAssertException(
                    Component.literal("timed out: the test did not succeed"), (int) helper.getTick());
        });
    }
}
