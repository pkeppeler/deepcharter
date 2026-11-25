package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.client.layer.FadeHold;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Server-side tests of the breach fade's hold decision (#1185, ADR 0089), which is plain logic: given how far the fade has come and
 * whether the client reports itself loaded, does the fade wait. {@code BreachFadeHoldClientTest} runs the same cases through
 * {@code BreachEffects}.
 */
public class FadeHoldTest {
    private static final int BLACK_END = 12;

    /** Drives {@code elapsed} like the fade does: it advances unless the hold answers yes. Returns the elapsed after {@code ticks}. */
    private static int run(FadeHold hold, int elapsed, int ticks, boolean loaded) {
        int now = elapsed;
        for (int i = 0; i < ticks; i++) {
            hold.clockTick();
            if (!hold.holds(now, BLACK_END, loaded)) {
                now++;
            }
        }
        return now;
    }

    /** The fade-in and black stretch never hold: the flag that arms the hold survives them. */
    @GameTest
    public void theHoldWaitsOutTheBlackStretchThenEngages(GameTestHelper helper) {
        FadeHold hold = new FadeHold();
        hold.reset();
        hold.respawned();
        hold.begin(true);
        int elapsed = run(hold, 0, BLACK_END + 30, false);
        expect(helper, elapsed == BLACK_END, "the fade should wait at %d, it reached %d", BLACK_END, elapsed);
        expect(helper, hold.heldTicks() == 30, "expected 30 held ticks, got %d", hold.heldTicks());
        helper.succeed();
    }

    /** The load finishes: the hold lets go at once and does not come back. */
    @GameTest
    public void theHoldReleasesWhenTheClientIsLoaded(GameTestHelper helper) {
        FadeHold hold = new FadeHold();
        hold.reset();
        hold.respawned();
        hold.begin(true);
        int elapsed = run(hold, 0, BLACK_END + 20, false);
        elapsed = run(hold, elapsed, 5, true);
        expect(helper, elapsed == BLACK_END + 5, "the fade should run on after the load, it is at %d", elapsed);
        elapsed = run(hold, elapsed, 5, false);
        expect(helper, elapsed == BLACK_END + 10, "a released hold came back, the fade is at %d", elapsed);
        helper.succeed();
    }

    /** No respawn is ever seen, however loaded the client says it is: the hold lasts to the cap. */
    @GameTest
    public void withNoRespawnTheHoldLastsToTheCap(GameTestHelper helper) {
        FadeHold hold = new FadeHold();
        hold.reset();
        hold.begin(true);
        int elapsed = run(hold, 0, BLACK_END + FadeHold.MAX_HOLD_TICKS, true);
        expect(helper, elapsed == BLACK_END, "the fade should still wait, it is at %d", elapsed);
        expect(helper, hold.heldTicks() == FadeHold.MAX_HOLD_TICKS, "held %d ticks", hold.heldTicks());
        elapsed = run(hold, elapsed, 3, true);
        expect(helper, elapsed == BLACK_END + 3, "the fade should run on past the cap, it is at %d", elapsed);
        helper.succeed();
    }

    /** The respawn comes after the black stretch: the hold waits for it, then for the load. */
    @GameTest
    public void aLateRespawnThenTheLoadReleasesTheHold(GameTestHelper helper) {
        FadeHold hold = new FadeHold();
        hold.reset();
        hold.begin(true);
        int elapsed = run(hold, 0, BLACK_END + 10, true);
        expect(helper, elapsed == BLACK_END, "loaded with no respawn seen should still hold, at %d", elapsed);
        hold.respawned();
        elapsed = run(hold, elapsed, 5, false);
        expect(helper, elapsed == BLACK_END, "the new level is loading, the fade moved to %d", elapsed);
        elapsed = run(hold, elapsed, 1, true);
        expect(helper, elapsed == BLACK_END + 1, "the load is done, the fade is at %d", elapsed);
        helper.succeed();
    }

    /** A respawn just before the fade, with the level loaded: nothing waits. */
    @GameTest
    public void aRespawnBeforeTheFadeAndAlreadyLoadedHoldsNothing(GameTestHelper helper) {
        FadeHold hold = new FadeHold();
        hold.reset();
        hold.respawned();
        hold.begin(true);
        int elapsed = run(hold, 0, BLACK_END + 10, true);
        expect(helper, elapsed == BLACK_END + 10 && hold.heldTicks() == 0, "the fade waited: at %d", elapsed);
        helper.succeed();
    }

    /** A respawn long before the fade is not this crossing's. */
    @GameTest
    public void aStaleRespawnDoesNotCount(GameTestHelper helper) {
        FadeHold hold = new FadeHold();
        hold.reset();
        hold.respawned();
        for (int i = 0; i < 200; i++) {
            hold.clockTick();
        }
        hold.begin(true);
        int elapsed = run(hold, 0, BLACK_END + 5, true);
        expect(helper, elapsed == BLACK_END, "a stale respawn counted, the fade is at %d", elapsed);
        helper.succeed();
    }

    /** A crossing in place has no fade and so no hold. */
    @GameTest
    public void aCrossingWithNoFadeHoldsNothing(GameTestHelper helper) {
        FadeHold hold = new FadeHold();
        hold.reset();
        hold.begin(false);
        int elapsed = run(hold, 0, BLACK_END + 5, false);
        expect(helper, elapsed == BLACK_END + 5, "a fadeless crossing held at %d", elapsed);
        helper.succeed();
    }
}
