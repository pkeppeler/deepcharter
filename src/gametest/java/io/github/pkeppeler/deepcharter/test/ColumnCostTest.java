package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.test.mixin.MinecraftServerAccess;
import io.github.pkeppeler.deepcharter.test.support.ColumnCost;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;

/** Server GameTests for {@link ColumnCost}'s hold on the autosave. */
public class ColumnCostTest {
    /** Its own batch, so no other test's run holds the autosave while the check reads it, and a batch cap cannot split the two. */
    private static final String AUTOSAVE_HOLD = "deepcharter-test:autosave_hold";

    /** No test dimension has this name, so the run throws. */
    private static final String NO_SUCH_WORLD = "no_such_world";

    private static final AtomicBoolean HOLD_STARTED = new AtomicBoolean();

    /** Holds the autosave as a measured run does and ends without releasing it: only {@code finish} can. */
    @GameTest(environment = AUTOSAVE_HOLD)
    public void aMeasuredRunHoldsTheAutosaveUntilItsTestEnds(GameTestHelper helper) {
        ColumnCost.tickBody(helper, List.of(NO_SUCH_WORLD), List.of(), 1, results -> {});
        HOLD_STARTED.set(true);
        helper.succeed();
    }

    /** The countdown is back once that test has ended, so {@code GameTestInfoMixin}'s {@code finish} released the hold. */
    @GameTest(environment = AUTOSAVE_HOLD, maxTicks = FarChunks.AWAIT_BUDGET_TICKS)
    public void theAutosaveCountdownIsBackOnceTheHoldingTestEnds(GameTestHelper helper) {
        FarChunks.Deadline deadline = FarChunks.deadline();
        MinecraftServerAccess server = (MinecraftServerAccess) helper.getLevel().getServer();
        TestTicks.each(helper, () -> {
            boolean restored = HOLD_STARTED.get() && !isHeld(server);
            deadline.await(
                    helper,
                    helper.getLevel(),
                    restored,
                    () -> HOLD_STARTED.get()
                            ? String.format(
                                    "the autosave is still held after its test ended: %d ticks to go",
                                    countdown(server))
                            : "the holding test never started");
            if (restored) {
                helper.succeed();
            }
        });
    }

    /**
     * A measured run that throws fails its test and the hold ends with it (tests.md: every exit path, a throw included). The body is
     * called here, where the throw can be caught: a runner turns an exception from a tick body into a failed test. The release is the
     * one {@code GameTestInfoMixin}'s {@code finish} calls on any exit.
     */
    @GameTest(environment = AUTOSAVE_HOLD)
    public void aMeasuredRunThatThrowsFailsItsTestAndReleasesTheAutosave(GameTestHelper helper) {
        AABB bounds = helper.getBounds();
        Runnable body = ColumnCost.tickBody(helper, List.of(NO_SUCH_WORLD), List.of(), 1, results -> {});
        expect(helper, ColumnCost.holds(bounds), "the measured run did not hold the autosave");
        boolean threw = throwsAssertion(body);
        expect(helper, threw, "a measured run in a world that does not exist did not throw");
        // The runner may tick the body once more before it ends the test: that tick must fail the same way, not on a
        // level that never arrived.
        boolean threwAgain = throwsAssertion(body);
        expect(helper, threwAgain, "a second tick after the throw did not fail with the same assertion");
        expect(
                helper,
                ColumnCost.holds(bounds),
                "the throw ended the hold itself, before the test's finish: it is only the release's");
        ColumnCost.release(bounds);
        expect(helper, !ColumnCost.holds(bounds), "the failed run's hold is still on after its release");
        helper.succeed();
    }

    private static boolean throwsAssertion(Runnable body) {
        try {
            body.run();
            return false;
        } catch (GameTestAssertException expected) {
            return true;
        }
    }

    /** The countdown still falls by one a tick while held, so a held one is near Integer.MAX_VALUE, not at it. */
    private static boolean isHeld(MinecraftServerAccess server) {
        return countdown(server) > Integer.MAX_VALUE / 2;
    }

    private static int countdown(MinecraftServerAccess server) {
        return server.deepcharter$ticksUntilAutosave();
    }
}
