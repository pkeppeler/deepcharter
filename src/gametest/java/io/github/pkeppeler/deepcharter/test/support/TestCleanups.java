package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;

/** Per-test cleanups for what lives outside the test's box (a pod a winch put on the shared colony pad), run from {@code GameTestInfoMixin}'s {@code finish} on every exit. Server thread only. */
public final class TestCleanups {
    private static final Map<AABB, List<Runnable>> BY_TEST = new HashMap<>();

    private TestCleanups() {}

    /** Runs {@code cleanup} when the test of {@code helper} ends, however it ends; it may find nothing made. */
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
