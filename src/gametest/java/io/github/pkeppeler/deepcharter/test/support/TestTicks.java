package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/** Per-tick test bodies that do not pile up in the test's schedule (#548). */
public final class TestTicks {
    private TestTicks() {}

    /**
     * Runs {@code body} on every tick from this one until the test finishes, with {@code GameTestHelper.onEachTick}'s timing and
     * failure behaviour: the first run is this tick, after the test method returns, and an exception thrown by the body fails the
     * test. Call it from the test method, not from a scheduled runnable.
     *
     * <p>{@code onEachTick} puts one runnable per remaining tick of {@code maxTicks} into {@code GameTestInfo.runAtTickTimeMap}, and
     * the runner keeps every finished test until the run ends. This keeps one entry. A runnable cannot reschedule itself:
     * {@code GameTestInfo.tickInternal} iterates that map and removes each entry after it runs, so a put during the iteration
     * corrupts it. A sequence event runs after the iteration in the same tick, so it does the put for the next tick.
     */
    public static void each(GameTestHelper helper, Runnable body) {
        helper.runAtTickTime(helper.getTick(), body);
        helper.startSequence().thenWaitUntil(() -> {
            helper.runAtTickTime(helper.getTick() + 1, body);
            // Never completes: a sequence that completes is dropped, and the next tick has no event to reschedule it.
            throw new GameTestAssertException(
                    Component.literal("each-tick body still running"), (int) helper.getTick());
        });
    }
}
