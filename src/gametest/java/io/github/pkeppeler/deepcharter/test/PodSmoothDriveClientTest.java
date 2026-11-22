package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD_JUMP;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PacketJitter;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest for #961: a pod drives as smoothly on a client as a boat. The real client pilots one pod and watches a mock
 * player's pod beside it, over flat ground, climbing on the rotor, and drilling into a wall. Every client tick the test samples what a
 * renderer would draw, the pod's position at four partial ticks, for the pilot (the camera follows the player riding) and for the
 * watched pod, and asserts the drawn position never steps backward along the drive and never steps further in one frame than the
 * pod's speed allows, and that a flat drive is never still for a tick it should be moving.
 *
 * <p>The harness ticks the server and client in lockstep, so the network is as regular as it never is in play. {@link PacketJitter}
 * makes it irregular the way a real connection is: every third client tick the client handles no pod move and the next tick
 * handles two. Without a slide between the server's moves the pod then stands still for a tick and lunges the next.
 */
public class PodSmoothDriveClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodSmoothDriveClientTest.class);

    /** Every third client tick the jitter holds the pod moves. */
    private static final int JITTER_PERIOD = 3;

    /** Frames drawn per client tick: a renderer at 80 frames a second. */
    private static final float[] PARTIAL_TICKS = {0.25f, 0.5f, 0.75f, 1f};

    private static final int FRAMES = PARTIAL_TICKS.length;
    /** Client ticks after a drive starts before it is judged: the pod reaches speed and its slide fills. */
    private static final int SETTLE_TICKS = 15;

    private static final int FLAT_TICKS = 70;
    private static final int CLIMB_TICKS = 32;
    /** Long enough for three slabs of wall and the approach to it. */
    private static final int DRILL_TICKS = 190;

    private static final int WALL_AHEAD = 8;
    private static final int WALL_SLABS = 3;
    private static final int WALL_HEIGHT = 4;
    private static final int WALL_WIDTH = 14;

    /**
     * How far above the pod's own speed a drawn step may be: a frame drawn after a moment's stall catches up, but a smooth slide never
     * draws more than the speed plus rounding. The jitter's lunge is twice the speed.
     */
    private static final double STEP_MARGIN = 1.4;

    private static final double BACKWARD_TOLERANCE = 1e-4;
    /** A flat drive covers at least this share of the pod's speed in every tick past the settle. */
    private static final double MIN_TICK_SHARE = 0.5;

    private record Subject(String name, List<Vec3> frames) {
        Subject(String name) {
            this(name, new ArrayList<>());
        }
    }

    /** The two pods the drives use: the real client's, and the mock's beside it, with where each stood at the start. */
    private record Rig(TwoPlayerServer two, int ownPodId, int mockPodId, Vec3 ownStart, Vec3 mockStart) {}

    /** One kind of drive: what the real client holds, what the mock's input is, how many client ticks, and the pod's speed in it. */
    private record Drive(
            String name,
            List<Function<Options, KeyMapping>> keys,
            Input mockInput,
            int ticks,
            double speed,
            boolean climbing,
            boolean steady,
            boolean wall) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            PodMovementClientTest.moveToOpenGround(context, two);
            int mockPodId = PodMovementClientTest.mountBoth(two);
            ClientWait.until(
                    context,
                    "the client riding a pod with the mock's pod in view",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity
                            && ClientLight.clientLevel(client).getEntity(mockPodId) instanceof PodEntity);
            int ownPodId = context.computeOnClient(
                    client -> TestEntities.vehicle(ClientSession.player(client)).getId());
            Rig rig = new Rig(two, ownPodId, mockPodId, serverPosition(two, ownPodId), serverPosition(two, mockPodId));
            context.waitTicks(10);

            PodTuning.Movement movement = PodTuning.DEFAULT.movement();
            double flatSpeed = movement.horizontalSpeed();
            // Forward and up together: the pod's speed is the two at once.
            double climbSpeed = Math.hypot(flatSpeed, movement.maxClimbSpeed());
            PacketJitter.start(JITTER_PERIOD);
            try {
                drive(
                        context,
                        rig,
                        new Drive(
                                "flat drive",
                                List.of(options -> options.keyUp),
                                FORWARD,
                                FLAT_TICKS,
                                flatSpeed,
                                false,
                                true,
                                false));
                drive(
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
                                false));
                drive(
                        context,
                        rig,
                        new Drive(
                                "drilling into a wall",
                                List.of(options -> options.keyUp),
                                FORWARD,
                                DRILL_TICKS,
                                flatSpeed,
                                false,
                                false,
                                true));
            } finally {
                PacketJitter.stop();
                context.getInput().releaseKey(options -> options.keyUp);
                context.getInput().releaseKey(options -> options.keyJump);
                two.server().runOnServer(server -> two.mock().releaseInput());
            }
        }
    }

    /**
     * Puts both pods on their start, behind a wall when the drive drills, holds the keys, samples every frame of the drive, and asserts
     * what the pilot's camera and the watched pod drew.
     */
    private static void drive(ClientGameTestContext context, Rig rig, Drive drive) {
        TwoPlayerServer two = rig.two();
        two.server().runOnServer(server -> {
            two.mock().releaseInput();
            if (drive.wall()) {
                buildWall(server.overworld(), rig.ownStart());
            }
            put(server.overworld(), rig.ownPodId(), rig.ownStart());
            put(server.overworld(), rig.mockPodId(), rig.mockStart());
        });
        ClientWait.until(
                context,
                "both pods standing at their start",
                client -> TestEntities.require(ClientLight.clientLevel(client), rig.ownPodId())
                                        .position()
                                        .distanceToSqr(rig.ownStart())
                                < 0.04
                        && TestEntities.require(ClientLight.clientLevel(client), rig.mockPodId())
                                        .position()
                                        .distanceToSqr(rig.mockStart())
                                < 0.04);
        context.waitTicks(SETTLE_TICKS);

        Subject pilot = new Subject("the pilot's own view");
        Subject watched = new Subject("the watched pod");
        boolean[] drilled = {false};
        try {
            drive.keys().forEach(key -> context.getInput().holdKey(key));
            two.server().runOnServer(server -> two.mock().setInput(drive.mockInput()));
            for (int tick = 0; tick < drive.ticks(); tick++) {
                context.waitTick();
                context.runOnClient(client -> {
                    Entity watchedPod = TestEntities.require(ClientLight.clientLevel(client), rig.mockPodId());
                    for (float partial : PARTIAL_TICKS) {
                        pilot.frames().add(ClientSession.player(client).getPosition(partial));
                        watched.frames().add(watchedPod.getPosition(partial));
                    }
                    drilled[0] |= watchedPod instanceof PodEntity pod && pod.drilling();
                });
            }
        } finally {
            drive.keys().forEach(key -> context.getInput().releaseKey(key));
            two.server().runOnServer(server -> two.mock().releaseInput());
        }
        if (drive.wall()) {
            require(drilled[0], "The watched pod never drilled, so the drilling drive was not a drilling drive");
        }
        for (Subject subject : List.of(pilot, watched)) {
            expectSmooth(drive.name() + ", " + subject.name(), subject.frames(), drive);
        }
    }

    /** Sets the pod down at {@code at}, still, with no fall behind it. */
    private static void put(ServerLevel level, int podId, Vec3 at) {
        Entity pod = TestEntities.require(level, podId);
        pod.setPos(at);
        pod.setDeltaMovement(Vec3.ZERO);
        pod.fallDistance = 0;
    }

    /** A wall of stone across both pods' way, {@code WALL_AHEAD} blocks ahead and {@code WALL_SLABS} deep. */
    private static void buildWall(ServerLevel level, Vec3 start) {
        BlockPos from = BlockPos.containing(start).offset(-WALL_WIDTH / 2, 0, WALL_AHEAD);
        for (int x = 0; x < WALL_WIDTH + 10; x++) {
            for (int z = 0; z < WALL_SLABS; z++) {
                for (int y = 0; y < WALL_HEIGHT; y++) {
                    level.setBlock(from.offset(x, y, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
        }
    }

    private static Vec3 serverPosition(TwoPlayerServer two, int podId) {
        return two.server()
                .computeOnServer(server ->
                        TestEntities.require(server.overworld(), podId).position());
    }

    /**
     * Asserts the drawn frames past the settle: no step backward along the drive (south, and up when the drive climbs), no step
     * longer than {@link #STEP_MARGIN} times one frame's share of the drive's speed, and, when the drive is steady, a tick's whole
     * displacement of at least {@link #MIN_TICK_SHARE} of it.
     */
    private static void expectSmooth(String what, List<Vec3> frames, Drive drive) {
        double speed = drive.speed();
        boolean climbing = drive.climbing();
        boolean steady = drive.steady();
        double longest = 0;
        double shortestTick = Double.MAX_VALUE;
        double worstBackward = 0;
        double stepBound = STEP_MARGIN * speed / FRAMES;
        for (int i = SETTLE_TICKS * FRAMES + 1; i < frames.size(); i++) {
            Vec3 step = frames.get(i).subtract(frames.get(i - 1));
            longest = Math.max(longest, step.length());
            worstBackward = Math.min(worstBackward, climbing ? Math.min(step.z, step.y) : step.z);
            if (i % FRAMES == 0 && i >= (SETTLE_TICKS + 1) * FRAMES) {
                Vec3 tick = frames.get(i).subtract(frames.get(i - FRAMES));
                shortestTick = Math.min(shortestTick, tick.horizontalDistance());
            }
        }
        LOGGER.info(
                "PodSmoothDriveClientTest: {}: longest frame step {} (bound {}), shortest tick {} (floor {}), worst backward step {}",
                what,
                longest,
                stepBound,
                shortestTick,
                MIN_TICK_SHARE * speed,
                worstBackward);
        require(
                worstBackward >= -BACKWARD_TOLERANCE,
                what + ": the drawn pod stepped backward by " + -worstBackward + " blocks in one frame");
        require(
                longest <= stepBound,
                what + ": the drawn pod stepped " + longest + " blocks in one frame, over the " + stepBound
                        + " its speed allows");
        if (steady) {
            require(
                    shortestTick >= MIN_TICK_SHARE * speed,
                    what + ": the drawn pod covered only " + shortestTick
                            + " blocks in one tick of a steady drive, under " + MIN_TICK_SHARE * speed);
        }
    }
}
