package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;

/**
 * Per-test state that lives outside the test's own box, such as an entity a winch carried onto the shared colony pad, removed on
 * every exit of the test, including a throw and a timeout: {@link #run} is called from {@code GameTestInfoMixin}'s {@code finish}. Server
 * thread only.
 */
public final class TestCleanups {
    private static final Map<AABB, List<Runnable>> BY_TEST = new HashMap<>();

    private TestCleanups() {}

    /** Runs {@code cleanup} when the test of {@code helper} ends, however it ends. It reads what the test made by then, so it may find nothing. */
    public static void onFinish(GameTestHelper helper, Runnable cleanup) {
        BY_TEST.computeIfAbsent(helper.getBounds(), bounds -> new ArrayList<>()).add(cleanup);
    }

    /** Runs and forgets the cleanups of the test with these bounds. */
    public static void run(AABB testBounds) {
        List<Runnable> mine = BY_TEST.remove(testBounds);
        if (mine != null) {
            mine.forEach(Runnable::run);
        }
    }
}
