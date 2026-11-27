package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import net.minecraft.gametest.framework.GameTestHelper;
import org.jspecify.annotations.Nullable;

/**
 * The first thing a one-shot check found wrong. {@code succeedWhen} catches what its body throws and polls again, so a check that
 * runs on one poll only (behind a flag, a counter or a latch) cannot throw: the next poll skips it and the test passes. Such a check
 * {@link #note}s its problem instead, and every poll calls {@link #ensure}, which throws until the test fails at its limit.
 */
public final class Findings {
    private @Nullable String first;

    /** Keeps {@code problem} unless an earlier one is kept. */
    public void note(String problem) {
        if (first == null) {
            first = problem;
        }
    }

    /** Throws the kept problem, on every call, or does nothing if none was noted. */
    public void ensure(GameTestHelper helper) {
        if (first != null) {
            throw failure(helper, "%s", first);
        }
    }
}
