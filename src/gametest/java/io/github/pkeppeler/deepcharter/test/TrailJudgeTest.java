package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import java.util.Arrays;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Server GameTest for #1356: {@link PodMovementClientTest}'s trail judgment passes moves that arrive a tick or two late and fails a
 * pod that keeps trailing. No world is built.
 */
public class TrailJudgeTest {
    /** The trail of a steady drive, every tick of every run before #1356. */
    private static final double STEADY = 0.6000568;

    /** A trail a real lag holds: one more tick of moves behind the server (the CI value of #1356). */
    private static final double LAGGING = 0.80001461872767;

    @GameTest
    public void lateMovesPassAndALagThatKeepsOnFails(GameTestHelper helper) {
        int ticks = PodMovementClientTest.DRIVE_TICKS;
        int allowed = PodMovementClientTest.MAX_TICKS_OVER;
        expect(helper, "a steady drive", steady(ticks), true);
        expect(helper, "one tick over at the end (#1356's CI run)", over(steady(ticks), ticks - 1, 1), true);
        double[] twoLate = steady(ticks);
        twoLate[20] = 0.7000568;
        twoLate[21] = 0.6500568;
        expect(helper, "two moves in a row a tick late", twoLate, true);
        expect(helper, "over for the most ticks allowed", over(steady(ticks), 10, allowed), true);
        expect(helper, "over for one tick more than allowed", over(steady(ticks), 10, allowed + 1), false);
        expect(
                helper,
                "over at the end for the most ticks allowed",
                over(steady(ticks), ticks - allowed, allowed),
                true);
        expect(helper, "two short spells", over(over(steady(ticks), 5, allowed), 5 + allowed + 1, allowed), true);
        expect(
                helper,
                "a lag from tick 23 to the end (#1241's even split)",
                over(steady(ticks), 23, ticks - 23),
                false);
        expect(helper, "a lag for the whole drive", over(steady(ticks), 0, ticks), false);
        helper.succeed();
    }

    private static double[] steady(int ticks) {
        double[] trail = new double[ticks];
        Arrays.fill(trail, STEADY);
        return trail;
    }

    private static double[] over(double[] trail, int from, int ticks) {
        Arrays.fill(trail, from, from + ticks, LAGGING);
        return trail;
    }

    private static void expect(GameTestHelper helper, String what, double[] trail, boolean passes) {
        int run = TrailJudge.longestRunOver(trail, PodMovementClientTest.MAX_LAG_BLOCKS);
        if ((run <= PodMovementClientTest.MAX_TICKS_OVER) != passes) {
            throw failure(
                    helper,
                    "%s: a trail over %s for %s ticks in a row (allowed %s) should %s: %s",
                    what,
                    PodMovementClientTest.MAX_LAG_BLOCKS,
                    run,
                    PodMovementClientTest.MAX_TICKS_OVER,
                    passes ? "pass" : "fail",
                    Arrays.toString(trail));
        }
    }
}
