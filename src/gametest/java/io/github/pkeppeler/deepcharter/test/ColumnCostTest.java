package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.test.mixin.MinecraftServerAccess;
import io.github.pkeppeler.deepcharter.test.support.ColumnCost;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;

/** Server GameTests for {@link ColumnCost}'s hold on the autosave. */
public class ColumnCostTest {
    /** Its own batch, so no other test's run holds the autosave while the check reads it. */
    private static final String AUTOSAVE_HOLD = "deepcharter-test:autosave_hold";

    /** No test dimension has this name, so the run throws. */
    private static final String NO_SUCH_WORLD = "no_such_world";

    /**
     * A measured run that throws fails its test and the hold ends with it (tests.md: every exit path, a throw included). The body is
     * called here, where the throw can be caught: a runner turns an exception from a tick body into a failed test. The release is the
     * one {@code GameTestInfoMixin}'s {@code finish} calls on any exit.
     */
    @GameTest(environment = AUTOSAVE_HOLD)
    public void aMeasuredRunThatThrowsFailsItsTestAndReleasesTheAutosave(GameTestHelper helper) {
        MinecraftServerAccess server = (MinecraftServerAccess) helper.getLevel().getServer();
        int before = server.deepcharter$ticksUntilAutosave();
        Runnable body = ColumnCost.tickBody(helper, List.of(NO_SUCH_WORLD), List.of(), 1, results -> {});
        expect(helper, isHeld(server), "the measured run did not hold the autosave: %d ticks to go", countdown(server));
        boolean threw = false;
        try {
            body.run();
        } catch (GameTestAssertException expected) {
            // The throw a runner reports as a failed test.
            threw = true;
        }
        expect(helper, threw, "a measured run in a world that does not exist did not throw");
        expect(
                helper,
                isHeld(server),
                "the throw ended the hold itself, before the test's finish: it is only the release's");
        ColumnCost.release(helper.getBounds());
        expect(
                helper,
                !isHeld(server) && countdown(server) <= before,
                "the autosave is still held after the failed run's release: %d ticks to go (%d before)",
                countdown(server),
                before);
        helper.succeed();
    }

    /** The countdown still falls by one a tick while held, so a held one is near Integer.MAX_VALUE, not at it. */
    private static boolean isHeld(MinecraftServerAccess server) {
        return countdown(server) > Integer.MAX_VALUE / 2;
    }

    private static int countdown(MinecraftServerAccess server) {
        return server.deepcharter$ticksUntilAutosave();
    }
}
