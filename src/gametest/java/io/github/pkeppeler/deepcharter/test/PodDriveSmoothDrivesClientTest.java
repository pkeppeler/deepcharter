package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD_JUMP;

import io.github.pkeppeler.deepcharter.pod.PodSteeringTuning;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.PacketJitter;
import io.github.pkeppeler.deepcharter.test.support.PodDrives;
import io.github.pkeppeler.deepcharter.test.support.PodDrives.Drive;
import io.github.pkeppeler.deepcharter.test.support.PodDrives.Rig;
import io.github.pkeppeler.deepcharter.test.support.PodDrives.Stall;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.List;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.entity.player.Input;

/**
 * Client GameTest for #961: a pod drives as smoothly on a client as a boat. The real client pilots one pod and watches a mock
 * player's pod beside it, over flat ground at the cruise and at the sprint's boost (#1276), climbing on the rotor, and drilling into
 * a wall, judged by {@link PodDrives}. Split from {@link PodDriveSmoothClientTest} (#1574) so that neither class outruns a CI shard.
 *
 * <p>The harness ticks the server and client in lockstep, so the network is as regular as it never is in play. {@link PacketJitter}
 * makes it irregular the way a real connection is: every third client tick the client handles no pod move and the next tick
 * handles two. Without a slide between the server's moves the pod then stands still for a tick and lunges the next.
 *
 * <p>It also covers the two late-server drives where the pod meets a block, a 6-tick stall across the arrival at a wall and a 5-tick
 * stall across a landing from 10 blocks (never drawn inside a block, never below the ground). The other late-server drives are
 * {@link PodDriveLateClientTest}'s.
 */
public class PodDriveSmoothDrivesClientTest implements FabricClientGameTest {
    private static final int FLAT_TICKS = 70;
    private static final int CLIMB_TICKS = 32;
    /** Long enough for three slabs of wall and the approach to it. */
    private static final int DRILL_TICKS = 190;

    /** A stall across the pod's arrival at the wall, which it reaches about 20 ticks into the drive. */
    private static final int WALL_STALL_AT_TICK = 16;

    private static final int WALL_STALL_TICKS = 6;

    /** A drop that lands about 10 ticks in, and a stall across the landing. */
    private static final double DROP_BLOCKS = 10;

    private static final int LANDING_STALL_AT_TICK = 17;
    private static final int LANDING_STALL_TICKS = 5;
    private static final int LANDING_DRIVE_TICKS = 50;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            PodMovementClientTest.moveToOpenGround(context, two);
            Rig rig = PodDrives.rig(context, two, PodMovementClientTest.mountBoth(two));
            context.waitTicks(10);

            PodTuning.Movement movement = PodTuning.DEFAULT.movement();
            double flatSpeed = movement.horizontalSpeed();
            double sprintSpeed = flatSpeed * PodSteeringTuning.DEFAULT.sprintBoost();
            // Forward and up together: the pod's speed is the two at once.
            double climbSpeed = Math.hypot(flatSpeed, movement.maxClimbSpeed());
            PacketJitter.start();
            try {
                PodDrives.drive(context, rig, Drive.forward("flat drive", FLAT_TICKS, flatSpeed, true, false, null));
                PodDrives.drive(context, rig, Drive.sprint("sprint drive", FLAT_TICKS, sprintSpeed, true, null));
                PodDrives.drive(
                        context,
                        rig,
                        Drive.forward(
                                "drilling into a wall through a server stall",
                                DRILL_TICKS,
                                flatSpeed,
                                false,
                                true,
                                new Stall(WALL_STALL_AT_TICK, WALL_STALL_TICKS, OptionalInt.empty(), 0, false, false)));
                PodDrives.drive(
                        context,
                        rig,
                        new Drive(
                                "landing during a server stall",
                                List.of(),
                                Input.EMPTY,
                                LANDING_DRIVE_TICKS,
                                flatSpeed,
                                false,
                                false,
                                false,
                                new Stall(
                                        LANDING_STALL_AT_TICK,
                                        LANDING_STALL_TICKS,
                                        OptionalInt.empty(),
                                        DROP_BLOCKS,
                                        true,
                                        false)));
                PodDrives.drive(
                        context,
                        rig,
                        new Drive(
                                "climb on the rotor",
                                List.of(options -> options.keyUp, options -> options.keyJump),
                                FORWARD_JUMP,
                                CLIMB_TICKS,
                                climbSpeed,
                                true,
                                false,
                                false,
                                null));
                PodDrives.drive(
                        context, rig, Drive.forward("drilling into a wall", DRILL_TICKS, flatSpeed, false, true, null));
            } finally {
                PacketJitter.stop();
                PodDrives.releaseAll(context, rig);
            }
        }
    }
}
