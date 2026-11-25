package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodGlideProbe;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.DriveJudge;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTest for #1112: late-server drives replayed move by move through the client's {@code PodGlide}, on a pod held out of the
 * world high above the test, so nothing but the glide moves it. Each drive is a pattern of extra lateness that the 1-D model of
 * {@code PodGlide} under {@code PacketJitter} (ADR 0082) found, and a client test meets it only when CI's server happens to run that
 * late. No tick may draw the pod further past the last move than the coast's reach. In a steady drive, every tick past the settle is
 * also held to half the speed, measured as the drive tests ({@code PodDrives}) measure it; the pod never stands at the reach in one,
 * so no tick is left out.
 *
 * <p>A move {@code n} is the server's position {@code n} ticks into the drive at the stock speed, rounded to the network's position
 * quantum as the client receives it, and the reported speed is the stock speed as it arrives rounded. Each array holds, per move, the
 * client tick before which the glide is handed it.
 */
public class PodGlideTraceTest {
    /** The stock speed as the server's pod moves, a float, and as the client receives it, rounded by {@code LpVec3}. */
    private static final double SPEED = 0.2f;

    private static final double REPORTED_SPEED = 2.0 * 19660 / 32766 - 1;

    private static final double QUANTA_PER_BLOCK = 4096;

    /** The drive's ticks, and the first one judged: the pod is at speed and its slide filled. */
    private static final int TICKS = 90;

    private static final int FIRST_JUDGED_TICK = 16;

    /** Partial tick of the frame each tick is measured from, to the same frame of the next: three quarters of it is the tick before. */
    private static final double FRAME = 0.25;

    private static final double FLOOR_SHARE = 0.5;

    /** Blocks above the test's floor the pod is held at: nothing for its coast to run into. */
    private static final int HELD_ABOVE = 40;

    /**
     * Ticks after the last late move a pod has to be back on its on-time course: the {@code MAX_TICKS_OVER} of {@code
     * PodMovementClientTest}'s trail window, rounded up.
     */
    private static final int RECOVERY_TICKS = 6;

    /** Blocks a recovered pod may still be behind its on-time course: {@code PodMovementClientTest}'s trail margin. */
    private static final double RECOVERED_WITHIN = 0.05;

    /** The drive with no extra lateness: each move two ticks after the server sends it, and the jitter's held tick one more. */
    private static final int[] ON_TIME = {
        2, 3, 5, 5, 6, 8, 8, 9, 11, 11, 12, 14, 14, 15, 17, 17, 18, 20, 20, 21, 23, 23, 24, 26, 26, 27, 29, 29, 30, 32,
        32, 33, 35, 35, 36, 38, 38, 39, 41, 41, 42, 44, 44, 45, 47, 47, 48, 50, 50, 51, 53, 53, 54, 56, 56, 57, 59, 59,
        60, 62, 62, 63, 65, 65, 66, 68, 68, 69, 71, 71, 72, 74, 74, 75, 77, 77, 78, 80, 80, 81, 83, 83, 84, 86, 86, 87,
        89, 89
    };

    /**
     * {@link #ON_TIME} with four moves a tick late each, a slip that the server closes with three moves in one tick (44). Each capped
     * catch-up step split the gap evenly as if the target stood still, which for a target moving at the pod's speed settled on a step
     * of exactly that speed: the pod trailed its on-time course by a tick of speed for the rest of the drive. The split now counts
     * the target's own advance, so every step closes some of the gap.
     */
    private static final int[] SLIP_CLOSED_AT_ONCE = {
        2, 3, 5, 5, 6, 8, 8, 9, 11, 11, 12, 14, 14, 15, 17, 17, 18, 20, 20, 21, 23, 23, 24, 26, 26, 27, 29, 29, 30, 32,
        32, 33, 35, 35, 36, 38, 38, 39, 41, 42, 44, 44, 44, 45, 47, 47, 48, 50, 50, 51, 53, 53, 54, 56, 56, 57, 59, 59,
        60, 62, 62, 63, 65, 65, 66, 68, 68, 69, 71, 71, 72, 74, 74, 75, 77, 77, 78, 80, 80, 81, 83, 83, 84, 86, 86, 87,
        89, 89
    };

    /**
     * A 3-tick stall that ends in a burst big enough for the catch-up cap: a cap and then a sliver of the gap drew the sliver's tick
     * at 0.095. Capped steps of a moving pod now split the gap evenly.
     */
    private static final int[] CAPPED_CATCH_UP = {
        2, 3, 5, 5, 6, 8, 8, 9, 11, 11, 12, 14, 14, 15, 17, 17, 18, 20, 20, 21, 23, 23, 24, 26, 26, 27, 29, 29, 30, 32,
        32, 33, 35, 35, 36, 38, 39, 41, 41, 41, 45, 45, 45, 45, 47, 47, 48, 50, 50, 51, 53, 53, 54, 56, 56, 57, 59, 59,
        60, 62, 62, 63, 65, 65, 66, 68, 68, 69, 71, 71, 72, 74, 74, 75, 77, 77, 78, 80, 80, 81, 83, 83, 84, 86, 86, 87,
        89, 89
    };

    /**
     * A 3-tick stall with the server later still: the pod comes to a rounding step short of the tail's reach. Drawing nothing there
     * stood it (0.0 in tick 42), and a whole tick drew it 0.8002 past the last move in tick 44; it goes on by what is left of the
     * reach.
     */
    private static final int[] SHORT_OF_THE_REACH = {
        2, 3, 5, 5, 6, 8, 8, 9, 11, 11, 12, 14, 14, 15, 17, 17, 18, 20, 20, 21, 23, 23, 24, 26, 26, 27, 29, 29, 30, 32,
        32, 33, 35, 35, 36, 38, 38, 41, 45, 45, 45, 45, 45, 45, 47, 47, 48, 50, 50, 51, 53, 53, 54, 56, 56, 57, 59, 59,
        60, 62, 62, 63, 65, 65, 66, 68, 68, 69, 71, 71, 72, 74, 74, 75, 77, 77, 78, 80, 80, 81, 83, 83, 84, 86, 86, 87,
        89, 89
    };

    /**
     * {@link #SHORT_OF_THE_REACH} with the server late for eight ticks more: the pod goes on to the reach and stands there until the
     * next move. A go-on step of a whole tick drew it 0.8002 past the last move in tick 44, and one of the tail share 0.8002 in tick 52.
     */
    private static final int[] PAST_THE_REACH = {
        2, 3, 5, 5, 6, 8, 8, 9, 11, 11, 12, 14, 14, 15, 17, 17, 18, 20, 20, 21, 23, 23, 24, 26, 26, 27, 29, 29, 30, 32,
        32, 33, 35, 35, 36, 38, 38, 41, 45, 45, 45, 45, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 54, 56, 56, 57, 59, 59,
        60, 62, 62, 63, 65, 65, 66, 68, 68, 69, 71, 71, 72, 74, 74, 75, 77, 77, 78, 80, 80, 81, 83, 83, 84, 86, 86, 87,
        89, 89
    };

    /**
     * A connection a tick slower for four ticks ({@code PacketJitter.lag}), so moves come late while the pod catches up. A step split
     * against the moving target that left the pod under a tick short of it drew the rest as a sliver when the next move was late, 0.059
     * in tick 43. The step leaves at least a tick of speed.
     */
    private static final int[] LATE_IN_A_CATCH_UP = {
        2, 3, 5, 5, 6, 8, 8, 9, 11, 11, 12, 14, 14, 15, 17, 17, 18, 20, 20, 21, 23, 23, 24, 26, 26, 27, 29, 29, 30, 32,
        32, 33, 35, 35, 36, 38, 39, 40, 40, 43, 44, 44, 44, 45, 47, 47, 48, 50, 50, 51, 53, 53, 54, 56, 56, 57, 59, 59,
        60, 62, 62, 63, 65, 65, 66, 68, 68, 69, 71, 71, 72, 74, 74, 75, 77, 77, 78, 80, 80, 81, 83, 83, 84, 86, 86, 87,
        89, 89
    };

    @GameTest
    public void aCappedCatchUpSplitsItsGapEvenly(GameTestHelper helper) {
        expectSteady(helper, "a capped catch-up after a stall", CAPPED_CATCH_UP);
        helper.succeed();
    }

    @GameTest
    public void aMoveLateInTheCatchUpFindsNoSliver(GameTestHelper helper) {
        expectSteady(helper, "a late move in a catch-up", LATE_IN_A_CATCH_UP);
        helper.succeed();
    }

    @GameTest
    public void aPodShortOfTheReachGoesOnToIt(GameTestHelper helper) {
        expectSteady(helper, "a late server that leaves the pod short of the reach", SHORT_OF_THE_REACH);
        helper.succeed();
    }

    /** The pod stands at the reach by design here, so its ticks are not held to the floor. */
    @GameTest
    public void aPodPastTheReachStandsAtIt(GameTestHelper helper) {
        replay(helper, "a server late past the reach", PAST_THE_REACH);
        helper.succeed();
    }

    @GameTest
    public void aSlipThatClosesAtOnceIsCaughtUp(GameTestHelper helper) {
        String what = "a slip of late moves closed by a burst";
        double[] onTime = replay(helper, "the on-time drive", ON_TIME);
        double[] slipped = expectSteady(helper, what, SLIP_CLOSED_AT_ONCE);
        int lastLate = 0;
        for (int n = 0; n < ON_TIME.length; n++) {
            if (SLIP_CLOSED_AT_ONCE[n] != ON_TIME[n]) {
                lastLate = Math.max(lastLate, SLIP_CLOSED_AT_ONCE[n]);
            }
        }
        for (int tick = lastLate + RECOVERY_TICKS; tick < TICKS; tick++) {
            double behind = onTime[tick] - slipped[tick];
            if (behind > RECOVERED_WITHIN) {
                throw failure(
                        helper,
                        "%s: the pod was %s blocks behind its on-time course in tick %s, %s ticks after the last late move",
                        what,
                        behind,
                        tick,
                        tick - lastLate);
            }
        }
        helper.succeed();
    }

    /** {@link #replay}, then fails on the first judged tick under the floor; returns the replay's positions. */
    private static double[] expectSteady(GameTestHelper helper, String what, int[] handedAt) {
        double[] drawn = replay(helper, what, handedAt);
        double floor = FLOOR_SHARE * SPEED;
        for (int tick = FIRST_JUDGED_TICK; tick < TICKS; tick++) {
            double measured =
                    (1 - FRAME) * (drawn[tick - 1] - drawn[tick - 2]) + FRAME * (drawn[tick] - drawn[tick - 1]);
            if (!DriveJudge.reaches(measured, floor)) {
                throw failure(
                        helper,
                        "%s: the glide drew %s blocks in tick %s, under the floor of %s",
                        what,
                        measured,
                        tick,
                        floor);
            }
        }
        return drawn;
    }

    /**
     * Replays the moves handed over at {@code handedAt}, failing on the first tick that draws the pod further past the last move than
     * the coast's reach, and returns where the pod is drawn after each tick, along the drive.
     */
    private static double[] replay(GameTestHelper helper, String what, int[] handedAt) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, helper.getLevel());
        Vec3 origin = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(0, HELD_ABOVE, 0)));
        double[] moves = new double[handedAt.length];
        double z = 0.5;
        for (int n = 0; n < moves.length; n++) {
            moves[n] = Math.rint(z * QUANTA_PER_BLOCK) / QUANTA_PER_BLOCK - 0.5;
            z += SPEED;
        }
        pod.setPos(origin);
        pod.setDeltaMovement(0, 0, REPORTED_SPEED);
        InterpolationHandler glide = PodGlideProbe.glide(pod);
        double[] drawn = new double[TICKS];
        double furthest = DriveJudge.coastReach() * REPORTED_SPEED + DriveJudge.POSITION_QUANTUM;
        int next = 0;
        for (int tick = 0; tick < TICKS; tick++) {
            for (; next < moves.length && handedAt[next] == tick; next++) {
                glide.interpolateTo(PositionPath.of(origin.add(0, 0, moves[next])), pod.getYRot(), pod.getXRot(), true);
            }
            glide.interpolate();
            drawn[tick] = pod.getZ() - origin.z;
            double lead = pod.getZ() - origin.z - (next == 0 ? 0 : moves[next - 1]);
            if (lead > furthest) {
                throw failure(
                        helper,
                        "%s: the glide drew the pod %s blocks past the last move in tick %s, beyond the coast's reach of %s",
                        what,
                        lead,
                        tick,
                        furthest);
            }
        }
        return drawn;
    }
}
