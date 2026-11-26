package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.PacketJitter;
import io.github.pkeppeler.deepcharter.test.support.PodDrives;
import io.github.pkeppeler.deepcharter.test.support.PodDrives.Drive;
import io.github.pkeppeler.deepcharter.test.support.PodDrives.Lag;
import io.github.pkeppeler.deepcharter.test.support.PodDrives.Lateness;
import io.github.pkeppeler.deepcharter.test.support.PodDrives.Rig;
import io.github.pkeppeler.deepcharter.test.support.PodDrives.Stall;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Client GameTest for #1041 and #1112: a pod drives smoothly on a client through a server that runs late, judged by {@link PodDrives}
 * over {@link PacketJitter}'s irregular network, as {@link PodDriveSmoothClientTest} judges an on-time one. A stall holds every move for
 * a few client ticks and hands them over in one ({@link PacketJitter#stall}); a lag makes the connection slower ({@link PacketJitter#lag}).
 * Stalls beyond 6 ticks are not covered: a longer one stands the pod for the rest of it, and a gap over {@code snapGap} blocks glides over
 * as before (ADR 0082).
 */
public class PodDriveLateClientTest implements FabricClientGameTest {
    /** Long enough for a stall in the middle of it and the pod to recover after. */
    private static final int STALL_DRIVE_TICKS = 90;

    /** Client ticks into a drive when a stall begins: past the settle, with the pod at speed. */
    private static final int STALL_AT_TICK = 40;

    /** A server a few ticks late: the pod coasts through it. */
    private static final int SHORT_STALL_TICKS = 3;

    /**
     * A short stall and the server's own lateness on top of it (#1112): the tall world's server ticks run long enough to add two, and the
     * pod's coast and its tail cover it with a tick to spare, so the drawn pod never stands.
     */
    private static final int LAGGED_STALL_TICKS = 5;

    /** A server later than the pod coasts: the pod may stand a few ticks, but never lunges when the moves arrive. */
    private static final int LONG_STALL_TICKS = 6;

    /**
     * A connection that gets this many client ticks slower (#1112): the pod sees no move for that long, then a move a tick, each as stale
     * as the gap was long, so it has coasted past every move it gets and must go on, never turn back to the stale position.
     */
    private static final int SLOWER_TICKS = 2;

    /**
     * A server that runs {@link #SLOWER_TICKS} late for this many client ticks and then catches up: stale moves, then the rest in one
     * burst, the pattern behind CI's 0.0674533 (ADR 0082).
     */
    private static final int CATCH_UP_AFTER_TICKS = 4;

    /** Client ticks of the stall that hides a stop: the keys are released one tick into it, so the server stops while the client is blind. */
    private static final int STOP_STALL_TICKS = 4;

    private static final int STOP_RELEASE_TICK = STALL_AT_TICK - 1;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            PodMovementClientTest.moveToOpenGround(context, two);
            Rig rig = PodDrives.rig(context, two, PodMovementClientTest.mountBoth(two));
            context.waitTicks(10);

            double speed = PodTuning.DEFAULT.movement().horizontalSpeed();
            PacketJitter.start();
            try {
                flat(
                        context,
                        rig,
                        speed,
                        "a short server stall",
                        true,
                        Stall.through(STALL_AT_TICK, SHORT_STALL_TICKS));
                flat(
                        context,
                        rig,
                        speed,
                        "a server that runs late and catches up",
                        true,
                        new Lag(STALL_AT_TICK, SLOWER_TICKS, OptionalInt.of(CATCH_UP_AFTER_TICKS)));
                flat(
                        context,
                        rig,
                        speed,
                        "a connection that gets slower",
                        true,
                        new Lag(STALL_AT_TICK, SLOWER_TICKS, OptionalInt.empty()));
                flat(
                        context,
                        rig,
                        speed,
                        "a lagging server stall",
                        true,
                        Stall.through(STALL_AT_TICK, LAGGED_STALL_TICKS));
                flat(context, rig, speed, "a long server stall", false, Stall.through(STALL_AT_TICK, LONG_STALL_TICKS));
                PodDrives.drive(
                        context,
                        rig,
                        Drive.forward(
                                "flat drive stopped during a server stall",
                                STALL_DRIVE_TICKS,
                                speed,
                                false,
                                false,
                                new Stall(
                                        STALL_AT_TICK,
                                        STOP_STALL_TICKS,
                                        OptionalInt.of(STOP_RELEASE_TICK),
                                        0,
                                        true,
                                        false)));
            } finally {
                PacketJitter.stop();
                PodDrives.releaseAll(context, rig);
            }
        }
    }

    /** A flat drive through {@code late}, held to the steady floor when {@code steady}. */
    private static void flat(
            ClientGameTestContext context, Rig rig, double speed, String through, boolean steady, Lateness late) {
        PodDrives.drive(
                context,
                rig,
                Drive.forward("flat drive through " + through, STALL_DRIVE_TICKS, speed, steady, false, late));
    }
}
