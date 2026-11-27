package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.pod.PodTuning;
import java.util.Arrays;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Server GameTest for #1356: {@link PodMovementClientTest}'s trail judgment passes moves that arrive a tick or two late and fails a
 * pod that keeps trailing. No world is built.
 */
public class TrailJudgeTest {
    /**
     * The trails below were recorded at a drive speed of 0.2 blocks a tick. A trail is ticks of the speed behind the
     * server, so each is scaled to the pod's speed now (0.4 since #1276), and the judgment's bounds scale with it.
     */
    private static final double RECORDED_SPEED = 0.2;

    private static final double SCALE = PodTuning.DEFAULT.movement().horizontalSpeed() / RECORDED_SPEED;

    /** The trail of a steady drive, every tick of every run before #1356. */
    private static final double STEADY = 0.6000568 * SCALE;

    /** A trail a real lag holds: one more tick of moves behind the server (the CI value of #1356). */
    private static final double LAGGING = 0.80001461872767 * SCALE;

    /**
     * The worst trail of every passing real run: a late spell mid-drive, then late moves to the very end of the drive
     * ({@code PacketJitter} {@code lag(1)} from tick 35, #1363), at the recorded speed.
     */
    private static final double[] REAL_LATE_TO_THE_END_RECORDED = {
        0.0,
        0.0,
        0.20000000298023224,
        0.4000000059604645,
        0.5000244230031967,
        0.550061047077179,
        0.5749817043542862,
        0.5874664485454559,
        0.5937332361936569,
        0.5968910455703735,
        0.598494365811348,
        0.5991983711719513,
        0.5995747894048691,
        0.5997874140739441,
        0.5999181419610977,
        0.6000079214572906,
        0.5999551564455032,
        0.5999531894922256,
        0.599976621568203,
        0.6000127531588078,
        0.6000552345067263,
        0.6000977158546448,
        0.700000062584877,
        0.7499756515026093,
        0.6799426934866872,
        0.6400202121212466,
        0.6199613166786424,
        0.6099562845099564,
        0.6049781839782291,
        0.602513549264982,
        0.6013056474609746,
        0.6006040417990874,
        0.6002776545207595,
        0.6001388764342117,
        0.6000939029435539,
        0.6000958317508411,
        0.6000977605581284,
        0.7000001072883606,
        0.7499756962060928,
        0.7749879062175751
    };

    private static final double[] REAL_LATE_TO_THE_END = Arrays.stream(REAL_LATE_TO_THE_END_RECORDED)
            .map(trail -> trail * SCALE)
            .toArray();

    /**
     * The window is the first slide tick strictly under the margin, whichever side of the float widening the speed falls: 2 late
     * moves at 0.2 land exactly on 0.05 at tick 3, so tick 4.
     */
    @GameTest
    public void slideTicksCountsTheFirstTickStrictlyUnder(GameTestHelper helper) {
        int steps = 2;
        for (double speed : new double[] {0.2, 0.2f}) {
            int ticks = TrailJudge.slideTicks(2 * speed, 0.05, steps);
            if (ticks != 4) {
                throw failure(
                        helper,
                        "2 late moves at speed %s with %s slide steps: %s ticks, expected 4",
                        speed,
                        steps,
                        ticks);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void lateMovesPassAndALagThatKeepsOnFails(GameTestHelper helper) {
        int ticks = PodMovementClientTest.DRIVE_TICKS;
        int allowed = PodMovementClientTest.MAX_TICKS_OVER;
        expect(helper, "a steady drive", steady(ticks), true);
        expect(helper, "one tick over at the end (#1356's CI run)", over(steady(ticks), ticks - 1, 1), true);
        double[] twoLate = steady(ticks);
        twoLate[20] = 0.7000568 * SCALE;
        twoLate[21] = 0.6500568 * SCALE;
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
        double[] lockDippingEverySixth = over(steady(ticks), 10, ticks - 10);
        for (int tick = 15; tick < ticks; tick += 6) {
            lockDippingEverySixth[tick] = STEADY;
        }
        expect(helper, "a lag from tick 10 to the end, back to steady every 6th tick", lockDippingEverySixth, false);
        double[] alternating = steady(ticks);
        for (int tick = 0; tick < ticks; tick += 2) {
            alternating[tick] = LAGGING;
        }
        expect(helper, "a lag on every other tick", alternating, false);
        expect(helper, "1.5 blocks for 3 ticks", fill(steady(ticks), 20, 3, 1.5 * SCALE), false);
        expect(helper, "late moves to the end (a real run at 0.2, worst 0.775)", REAL_LATE_TO_THE_END, true);
        expect(
                helper,
                "exactly at the bound for the whole drive",
                fill(steady(ticks), 0, ticks, PodMovementClientTest.MAX_LAG_BLOCKS),
                true);
        helper.succeed();
    }

    private static double[] steady(int ticks) {
        double[] trail = new double[ticks];
        Arrays.fill(trail, STEADY);
        return trail;
    }

    private static double[] over(double[] trail, int from, int ticks) {
        return fill(trail, from, ticks, LAGGING);
    }

    private static double[] fill(double[] trail, int from, int ticks, double blocks) {
        Arrays.fill(trail, from, from + ticks, blocks);
        return trail;
    }

    private static void expect(GameTestHelper helper, String what, double[] trail, boolean passes) {
        if (TrailJudge.fault(trail).isEmpty() != passes) {
            throw failure(
                    helper,
                    "%s: a trail %s should %s: %s",
                    what,
                    TrailJudge.summary(trail),
                    passes ? "pass" : "fail",
                    Arrays.toString(trail));
        }
    }
}
