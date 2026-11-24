package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.mixin.MinecraftServerAccess;
import io.github.pkeppeler.deepcharter.test.support.ColumnCost;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Server GameTests for {@link ColumnCost}'s hold on the autosave. */
public class ColumnCostTest {
    /** Its own batch, so no other test's run holds the autosave while the check reads it, and a batch cap cannot split the two. */
    private static final String AUTOSAVE_HOLD = "deepcharter-test:autosave_hold";

    /** No test dimension has this name, so the run throws. */
    private static final String NO_SUCH_WORLD = "no_such_world";

    private static final AtomicBoolean HOLD_STARTED = new AtomicBoolean();

    /**
     * Starts a measured run in a world that does not exist, so the run throws and fails its test before it ends. Optional, so that
     * failure does not fail the suite; the case below judges it.
     */
    @GameTest(environment = AUTOSAVE_HOLD, required = false)
    public void aMeasuredRunThatThrowsFailsItsTest(GameTestHelper helper) {
        HOLD_STARTED.set(true);
        ColumnCost.run(helper, List.of(NO_SUCH_WORLD), List.of(), 1, results -> {});
    }

    /** The countdown is back once the failed run's test has ended (tests.md: every exit path, a throw included). */
    @GameTest(environment = AUTOSAVE_HOLD, maxTicks = FarChunks.AWAIT_BUDGET_TICKS)
    public void theAutosaveCountdownIsBackAfterAMeasuredRunFails(GameTestHelper helper) {
        FarChunks.Deadline deadline = FarChunks.deadline();
        MinecraftServerAccess server = (MinecraftServerAccess) helper.getLevel().getServer();
        TestTicks.each(helper, () -> {
            // The countdown still falls by one a tick while held, so a held one is near Integer.MAX_VALUE, not at it.
            int countdown = server.deepcharter$ticksUntilAutosave();
            boolean restored = HOLD_STARTED.get() && countdown <= Integer.MAX_VALUE / 2;
            deadline.await(
                    helper,
                    helper.getLevel(),
                    restored,
                    () -> HOLD_STARTED.get()
                            ? String.format(
                                    "the autosave is still held after the measured run's test failed: %d ticks to go",
                                    countdown)
                            : "the measured run never started");
            if (restored) {
                helper.succeed();
            }
        });
    }
}
