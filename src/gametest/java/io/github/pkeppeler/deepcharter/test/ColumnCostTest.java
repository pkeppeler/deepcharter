package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

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

    private static final AtomicBoolean HOLDER_ENDED = new AtomicBoolean();

    /**
     * Starts a measured run and ends the test before the run does, as a timeout or a failed {@code expect} would. The run has no
     * worlds, so it generates nothing and never ends by itself.
     */
    @GameTest(environment = AUTOSAVE_HOLD)
    public void aMeasuredRunHoldsTheAutosaveUntilItsTestEnds(GameTestHelper helper) {
        ColumnCost.run(helper, List.of(), List.of(), 1, results -> {});
        helper.succeed();
        HOLDER_ENDED.set(true);
    }

    /** The countdown is back once the test that held it has ended, however it ended (tests.md: every exit path). */
    @GameTest(environment = AUTOSAVE_HOLD, maxTicks = FarChunks.AWAIT_BUDGET_TICKS)
    public void theAutosaveCountdownIsBackAfterAMeasuredRunsTestEnds(GameTestHelper helper) {
        FarChunks.Deadline deadline = FarChunks.deadline();
        MinecraftServerAccess server = (MinecraftServerAccess) helper.getLevel().getServer();
        TestTicks.each(helper, () -> {
            boolean ended = HOLDER_ENDED.get();
            deadline.await(helper, helper.getLevel(), ended, () -> "the measured run's test never ended");
            if (!ended) {
                return;
            }
            // The countdown still falls by one a tick while held, so a held one is near Integer.MAX_VALUE, not at it.
            int countdown = server.deepcharter$ticksUntilAutosave();
            expect(
                    helper,
                    countdown <= Integer.MAX_VALUE / 2,
                    "the autosave is still held after the measured run's test ended: %d ticks to go",
                    countdown);
            helper.succeed();
        });
    }
}
