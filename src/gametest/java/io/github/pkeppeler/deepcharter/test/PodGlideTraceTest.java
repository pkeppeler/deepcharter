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
 * Server GameTest for #1112: two late-server drives replayed move by move through the client's {@code PodGlide}, on a pod held out of
 * the world high above the test, so nothing but the glide moves it. Each drive is a pattern of extra lateness that the 1-D model of
 * {@code PodGlide} under {@code PacketJitter} (ADR 0082) found, and a client test meets it only when CI's server happens to run that
 * late. Every tick past the settle is held to half the speed, measured as {@code PodDriveSmoothClientTest} measures it; the pod never
 * reaches the coast's reach in either, so no tick is left out.
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
     * A 3-tick stall with the server later still: the pod comes to a rounding step short of the tail's reach, where a go-on step of a
     * whole tick, the tail share or nothing stood it (0.050). It now goes on by what is left of the reach.
     */
    private static final int[] SHORT_OF_THE_REACH = {
        2, 3, 5, 5, 6, 8, 8, 9, 11, 11, 12, 14, 14, 15, 17, 17, 18, 20, 20, 21, 23, 23, 24, 26, 26, 27, 29, 29, 30, 32,
        32, 33, 35, 35, 36, 38, 38, 41, 45, 45, 45, 45, 45, 45, 47, 47, 48, 50, 50, 51, 53, 53, 54, 56, 56, 57, 59, 59,
        60, 62, 62, 63, 65, 65, 66, 68, 68, 69, 71, 71, 72, 74, 74, 75, 77, 77, 78, 80, 80, 81, 83, 83, 84, 86, 86, 87,
        89, 89
    };

    @GameTest
    public void aCappedCatchUpSplitsItsGapEvenly(GameTestHelper helper) {
        expectSteady(helper, "a capped catch-up after a stall", CAPPED_CATCH_UP);
        helper.succeed();
    }

    @GameTest
    public void aPodShortOfTheReachGoesOnToIt(GameTestHelper helper) {
        expectSteady(helper, "a late server that leaves the pod short of the reach", SHORT_OF_THE_REACH);
        helper.succeed();
    }

    /** Replays the moves handed over at {@code handedAt} and fails on the first judged tick under the floor. */
    private static void expectSteady(GameTestHelper helper, String what, int[] handedAt) {
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
        double[] stepOf = new double[TICKS];
        int next = 0;
        for (int tick = 0; tick < TICKS; tick++) {
            for (; next < moves.length && handedAt[next] == tick; next++) {
                glide.interpolateTo(PositionPath.of(origin.add(0, 0, moves[next])), pod.getYRot(), pod.getXRot(), true);
            }
            double before = pod.getZ();
            glide.interpolate();
            stepOf[tick] = pod.getZ() - before;
        }
        double floor = FLOOR_SHARE * SPEED;
        for (int tick = FIRST_JUDGED_TICK; tick < TICKS; tick++) {
            double measured = (1 - FRAME) * stepOf[tick - 1] + FRAME * stepOf[tick];
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
    }
}
