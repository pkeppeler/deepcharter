package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD_JUMP;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodGlideTuning;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
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
 *
 * <p>The late-server drives (#1041, ADR 0082) hold every move for a few client ticks and hand them over in one, as a server that runs
 * late does ({@link PacketJitter#stall}). They cover stalls of 3 and 5 ticks (the pod coasts through, the 5 on the coast's tail: steady floor and lunge bound hold),
 * 4 ticks across a key release (the pod ends past the server's stop by no more than a coast, steps back under half a frame's speed,
 * and settles on the server's), 6 ticks (the pod may stand, never lunges, keeps 80% of its progress and settles), 6 ticks across the
 * arrival at a wall, and 5 ticks across a landing from 10 blocks (never drawn inside a block, never below the ground). They do not cover
 * stalls beyond 6 ticks: a longer one stands the pod for the rest of it, and a gap over {@code snapGap} blocks glides over as before
 * (ADR 0082).
 */
public class PodDriveSmoothClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodDriveSmoothClientTest.class);

    /** Frames drawn per client tick: a renderer at 80 frames a second. */
    private static final float[] PARTIAL_TICKS = {0.25f, 0.5f, 0.75f, 1f};

    private static final int FRAMES = PARTIAL_TICKS.length;
    /** Client ticks after a drive starts before it is judged: the pod reaches speed and its slide fills. */
    private static final int SETTLE_TICKS = 15;

    /** Client ticks for a released key to reach the server and act there, with margin. */
    private static final int INPUT_LAG_TICKS = 5;

    private static final int FLAT_TICKS = 70;
    private static final int CLIMB_TICKS = 32;
    /** Long enough for three slabs of wall and the approach to it. */
    private static final int DRILL_TICKS = 190;

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

    /** Client ticks of the stall that hides a stop: the keys are released one tick into it, so the server stops while the client is blind. */
    private static final int STOP_STALL_TICKS = 4;

    private static final int STOP_RELEASE_TICK = STALL_AT_TICK - 1;

    /** A stall across the pod's arrival at the wall, which it reaches about 38 ticks into the drive. */
    private static final int WALL_STALL_AT_TICK = 34;

    private static final int WALL_STALL_TICKS = 6;

    /** A drop that lands about 10 ticks in, and a stall across the landing. */
    private static final double DROP_BLOCKS = 10;

    private static final int LANDING_STALL_AT_TICK = 17;
    private static final int LANDING_STALL_TICKS = 5;
    private static final int LANDING_DRIVE_TICKS = 50;

    /** Client ticks after a drive for a drawn pod that coasted too far to settle on the server's. */
    private static final int SETTLE_AFTER_TICKS = 40;

    /** How far the drawn pod may end from the server's, once it has settled. */
    private static final double SETTLE_TOLERANCE = 0.02;

    /** A stall drive's net progress, at least this share of what the speed covers. */
    private static final double MIN_PROGRESS_SHARE = 0.8;

    /** A pod that coasted too far gives it back at under this share of its speed, so it is a bounce too small to read as a reversal. */
    private static final double MAX_BOUNCE_SHARE = 0.5;

    /** Blocks the pod's drawn box is shrunk by before it is tested against blocks: touching a wall is not inside it. */
    private static final double INSIDE_MARGIN = 0.05;

    /** Blocks a pod is pushed away from where the client draws it, on either side of the catch-up's old cliff at 2, and past the snap gap. */
    private static final double[] PUSH_GAPS = {1.0, 2.0, 2.01, 5.0, 15.0, 20.0};

    /** Client ticks a push is watched: long enough for the largest eased gap to close. */
    private static final int PUSH_TICKS = 90;

    /** Client ticks a push beyond the snap gap has to arrive in: it glides over {@code steps} ticks as it always did. */
    private static final int SNAP_TICKS = 5;

    /** Two gaps this close in size draw pushes whose steps differ by no more than this: the catch-up has no cliff. */
    private static final double CLIFF_TOLERANCE = 0.02;

    /** Ticks a pinned drive holds the keys before the pin, and after it. */
    private static final int PIN_AFTER_TICKS = 30;

    private static final int PINNED_TICKS = 40;

    private static final int WALL_AHEAD = 8;
    private static final int WALL_SLABS = 3;
    private static final int WALL_HEIGHT = 4;
    private static final int WALL_WIDTH = 14;

    /**
     * How far above the pod's own speed a drawn step may be: a pod that fell behind catches up at {@code PodGlideTuning.catchUp()} times
     * its speed, 1.25, and the margin leaves room for the packet rounding. An uncapped catch-up after a stall lunges 1.5 times its
     * speed or more (#1041).
     */
    private static final double STEP_MARGIN = 1.4;

    private static final double BACKWARD_TOLERANCE = 1e-4;
    /** A flat drive covers at least this share of the pod's speed in every tick past the settle. */
    private static final double MIN_TICK_SHARE = 0.5;

    /** The pod an {@link PodEvents#AFTER_TICK} listener of this test holds in place, and where; none when the id is -1. */
    private static volatile int pinnedPodId = -1;

    private static volatile @Nullable Vec3 pinnedAt;

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
            boolean wall,
            @Nullable Late late) {
        /** A drive with the forward key held. */
        static Drive forward(String name, int ticks, double speed, boolean steady, boolean wall, @Nullable Late late) {
            return new Drive(name, List.of(options -> options.keyUp), FORWARD, ticks, speed, false, steady, wall, late);
        }
    }

    /**
     * A drive through a late server (#1041): where the stall begins and how long it lasts, when the keys are released ({@code -1} for
     * never), how far above its start the pod is dropped, whether the drawn pod must settle on the server's, and whether it must keep
     * its progress.
     */
    private record Late(
            int stallAt, int stallTicks, int releaseAt, double dropBlocks, boolean settles, boolean recovers) {
        boolean stops() {
            return releaseAt >= 0 || dropBlocks > 0;
        }
    }

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
            PodEvents.AFTER_TICK.register(pod -> {
                Vec3 at = pinnedAt;
                if (at != null && pod.getId() == pinnedPodId) {
                    pod.setPos(at);
                }
            });
            expectPushesEase(context, rig);
            expectPinnedPodSettles(context, rig);
            PacketJitter.start();
            try {
                drive(context, rig, Drive.forward("flat drive", FLAT_TICKS, flatSpeed, true, false, null));
                drive(
                        context,
                        rig,
                        Drive.forward(
                                "flat drive through a short server stall",
                                STALL_DRIVE_TICKS,
                                flatSpeed,
                                true,
                                false,
                                new Late(STALL_AT_TICK, SHORT_STALL_TICKS, -1, 0, true, true)));
                drive(
                        context,
                        rig,
                        Drive.forward(
                                "flat drive through a lagging server stall",
                                STALL_DRIVE_TICKS,
                                flatSpeed,
                                true,
                                false,
                                new Late(STALL_AT_TICK, LAGGED_STALL_TICKS, -1, 0, true, true)));
                drive(
                        context,
                        rig,
                        Drive.forward(
                                "flat drive through a long server stall",
                                STALL_DRIVE_TICKS,
                                flatSpeed,
                                false,
                                false,
                                new Late(STALL_AT_TICK, LONG_STALL_TICKS, -1, 0, true, true)));
                drive(
                        context,
                        rig,
                        Drive.forward(
                                "flat drive stopped during a server stall",
                                STALL_DRIVE_TICKS,
                                flatSpeed,
                                false,
                                false,
                                new Late(STALL_AT_TICK, STOP_STALL_TICKS, STOP_RELEASE_TICK, 0, true, false)));
                drive(
                        context,
                        rig,
                        Drive.forward(
                                "drilling into a wall through a server stall",
                                DRILL_TICKS,
                                flatSpeed,
                                false,
                                true,
                                new Late(WALL_STALL_AT_TICK, WALL_STALL_TICKS, -1, 0, false, false)));
                drive(
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
                                new Late(LANDING_STALL_AT_TICK, LANDING_STALL_TICKS, -1, DROP_BLOCKS, true, false)));
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
                                false,
                                null));
                drive(context, rig, Drive.forward("drilling into a wall", DRILL_TICKS, flatSpeed, false, true, null));
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
        Late late = drive.late();
        // A drop starts held in the air: the pods wait there without gravity, so the client has them in place before
        // they fall.
        double drop = late == null ? 0 : late.dropBlocks();
        Vec3 ownAt = rig.ownStart().add(0, drop, 0);
        Vec3 mockAt = rig.mockStart().add(0, drop, 0);
        two.server().runOnServer(server -> {
            two.mock().releaseInput();
            if (drive.wall()) {
                buildWall(server.overworld(), rig.ownStart());
            }
            put(server.overworld(), rig.ownPodId(), ownAt);
            put(server.overworld(), rig.mockPodId(), mockAt);
            TestEntities.require(server.overworld(), rig.ownPodId()).setNoGravity(drop > 0);
            TestEntities.require(server.overworld(), rig.mockPodId()).setNoGravity(drop > 0);
        });
        ClientWait.until(
                context,
                "both pods standing at their start",
                () -> context.computeOnClient(
                        client -> standsAt(client, rig.ownPodId(), ownAt) && standsAt(client, rig.mockPodId(), mockAt)),
                () -> "own pod on the client " + onClient(context, rig.ownPodId()) + ", on the server "
                        + serverPosition(two, rig.ownPodId()) + ", for " + ownAt + "; mock pod on the client "
                        + onClient(context, rig.mockPodId()) + ", on the server " + serverPosition(two, rig.mockPodId())
                        + ", for " + mockAt);
        context.waitTicks(SETTLE_TICKS);
        if (drop > 0) {
            two.server().runOnServer(server -> {
                TestEntities.require(server.overworld(), rig.ownPodId()).setNoGravity(false);
                TestEntities.require(server.overworld(), rig.mockPodId()).setNoGravity(false);
            });
        }

        Subject pilot = new Subject("the pilot's own view");
        Subject watched = new Subject("the watched pod");
        Subject ownPod = new Subject("the pilot's own pod");
        int[] inside = {0};
        boolean[] drilled = {false};
        try {
            drive.keys().forEach(key -> context.getInput().holdKey(key));
            two.server().runOnServer(server -> two.mock().setInput(drive.mockInput()));
            for (int tick = 0; tick < drive.ticks(); tick++) {
                if (late != null && tick == late.stallAt()) {
                    context.runOnClient(client -> PacketJitter.stall(late.stallTicks()));
                }
                if (late != null && tick == late.releaseAt()) {
                    drive.keys().forEach(key -> context.getInput().releaseKey(key));
                    two.server().runOnServer(server -> two.mock().releaseInput());
                }
                context.waitTick();
                context.runOnClient(client -> {
                    Entity watchedPod = TestEntities.require(ClientLight.clientLevel(client), rig.mockPodId());
                    Entity own = TestEntities.vehicle(ClientSession.player(client));
                    for (float partial : PARTIAL_TICKS) {
                        pilot.frames().add(ClientSession.player(client).getPosition(partial));
                        watched.frames().add(watchedPod.getPosition(partial));
                        ownPod.frames().add(own.getPosition(partial));
                        inside[0] += insideBlocks(watchedPod, partial) + insideBlocks(own, partial);
                    }
                    drilled[0] |= watchedPod instanceof PodEntity pod && pod.drilling();
                });
            }
        } finally {
            drive.keys().forEach(key -> context.getInput().releaseKey(key));
            two.server().runOnServer(server -> two.mock().releaseInput());
            // The server acts on the last input the client sent until the release reaches it; a pod put on its start
            // before that would drive on from the start.
            context.waitTicks(INPUT_LAG_TICKS);
        }
        if (drive.wall()) {
            require(drilled[0], "The watched pod never drilled, so the drilling drive was not a drilling drive");
        }
        if (late != null) {
            require(inside[0] == 0, drive.name() + ": a pod was drawn inside a block in " + inside[0] + " frames");
            if (late.stops()) {
                context.waitTicks(SETTLE_AFTER_TICKS);
                expectStopped(drive, late, rig, context, ownPod, watched);
                return;
            }
        }
        for (Subject subject : List.of(pilot, watched)) {
            expectSmooth(drive.name() + ", " + subject.name(), subject.frames(), drive);
        }
        if (late != null && late.recovers()) {
            for (Subject subject : List.of(ownPod, watched)) {
                expectProgress(drive.name() + ", " + subject.name(), subject.frames(), drive);
            }
        }
        if (late != null && late.settles()) {
            context.waitTicks(SETTLE_AFTER_TICKS);
            expectSettled(drive.name(), rig, context);
        }
    }

    /** 1 when the pod's drawn box, shrunk by {@link #INSIDE_MARGIN}, overlaps a block at {@code partial}; else 0. */
    private static int insideBlocks(Entity pod, float partial) {
        AABB box = pod.getDimensions(pod.getPose())
                .makeBoundingBox(pod.getPosition(partial))
                .deflate(INSIDE_MARGIN);
        return pod.level().getBlockCollisions(pod, box).iterator().hasNext() ? 1 : 0;
    }

    /** The drawn pods, once the drive is over and they have had time to settle, stand on the server's. */
    private static void expectSettled(String what, Rig rig, ClientGameTestContext context) {
        for (int podId : new int[] {rig.ownPodId(), rig.mockPodId()}) {
            Vec3 server = serverPosition(rig.two(), podId);
            Vec3 drawn = onClient(context, podId);
            require(
                    drawn.distanceTo(server) <= SETTLE_TOLERANCE,
                    what + ": pod " + podId + " is drawn at " + drawn + ", the server has it at " + server
                            + ", more than " + SETTLE_TOLERANCE + " apart after " + SETTLE_AFTER_TICKS + " ticks");
        }
    }

    /** A stall drive keeps its progress: the pod covers at least {@link #MIN_PROGRESS_SHARE} of what its speed does, past the settle. */
    private static void expectProgress(String what, List<Vec3> frames, Drive drive) {
        double covered = frames.getLast().z - frames.get(SETTLE_TICKS * FRAMES).z;
        double expected = MIN_PROGRESS_SHARE * drive.speed() * (drive.ticks() - SETTLE_TICKS);
        LOGGER.info("PodDriveSmoothClientTest: {}: covered {} (at least {})", what, covered, expected);
        require(covered >= expected, what + ": the drawn pod covered only " + covered + " blocks, under " + expected);
    }

    /**
     * A drive that stops during a stall (the keys released, or the ground reached): the drawn pods never go past where the server put them
     * by more than a coast ({@link #coastReach} of speed; none for a landing, since a coast stops short of the ground), each frame step
     * stays under the lunge bound, the way back is under {@link #MAX_BOUNCE_SHARE} of a frame's speed, and they end on the server's.
     */
    private static void expectStopped(
            Drive drive, Late late, Rig rig, ClientGameTestContext context, Subject ownPod, Subject watched) {
        boolean landing = late.dropBlocks() > 0;
        double coast = landing ? 0 : coastReach() * drive.speed();
        double bounceBound = MAX_BOUNCE_SHARE * drive.speed() / FRAMES;
        double stepBound = STEP_MARGIN * drive.speed() / FRAMES;
        for (Subject subject : List.of(ownPod, watched)) {
            int podId = subject == ownPod ? rig.ownPodId() : rig.mockPodId();
            Vec3 server = serverPosition(rig.two(), podId);
            double past = 0;
            double longest = 0;
            double backward = 0;
            List<Vec3> frames = subject.frames();
            for (int i = 1; i < frames.size(); i++) {
                Vec3 step = frames.get(i).subtract(frames.get(i - 1));
                past = Math.max(past, landing ? server.y - frames.get(i).y : frames.get(i).z - server.z);
                if (!landing) {
                    longest = Math.max(longest, step.length());
                    backward = Math.min(backward, step.z);
                }
            }
            String what = drive.name() + ", " + subject.name();
            LOGGER.info(
                    "PodDriveSmoothClientTest: {}: past the server's stop by {} (at most {}), longest frame step {} (bound {}), way back {} (at most {})",
                    what,
                    past,
                    coast + SETTLE_TOLERANCE,
                    longest,
                    stepBound,
                    -backward,
                    bounceBound);
            require(
                    past <= coast + SETTLE_TOLERANCE,
                    what + ": the drawn pod went " + past + " blocks past the server's");
            require(longest <= stepBound, what + ": the drawn pod stepped " + longest + " blocks in one frame");
            require(
                    -backward <= bounceBound,
                    what + ": the drawn pod stepped back " + -backward + " blocks in one frame");
        }
        expectSettled(drive.name(), rig, context);
    }

    /** Ticks of its speed a whole coast draws: the full-speed ticks and the tail's share of the last. */
    private static double coastReach() {
        PodGlideTuning tuning = PodGlideTuning.DEFAULT;
        return tuning.coastTicks() - 1 + tuning.coastTailShare();
    }

    /** Puts the pilot's pod back on its start and waits for the client to draw it there, still. */
    private static void returnToStart(ClientGameTestContext context, Rig rig) {
        rig.two().server().runOnServer(server -> put(server.overworld(), rig.ownPodId(), rig.ownStart()));
        ClientWait.until(
                context,
                "the pilot's pod standing at its start",
                () -> context.computeOnClient(client -> standsAt(client, rig.ownPodId(), rig.ownStart())),
                () -> "own pod on the client " + onClient(context, rig.ownPodId()) + ", on the server "
                        + serverPosition(rig.two(), rig.ownPodId()) + ", for " + rig.ownStart());
        context.waitTicks(SETTLE_TICKS);
    }

    /**
     * A pod that stands where it is drawn and is moved {@code gap} blocks (a push, a tow, a teleport) is eased over to its new place at no
     * more than a share of the gap a tick, and the pace is continuous in the gap: no jump at the old cliff of 2 blocks. Beyond
     * {@code snapGap} it glides over in a few ticks, as it always did.
     */
    private static void expectPushesEase(ClientGameTestContext context, Rig rig) {
        double stock = PodTuning.DEFAULT.movement().horizontalSpeed();
        PodGlideTuning tuning = PodGlideTuning.DEFAULT;
        double[] longest = new double[PUSH_GAPS.length];
        for (int i = 0; i < PUSH_GAPS.length; i++) {
            double gap = PUSH_GAPS[i];
            returnToStart(context, rig);
            Vec3 to = rig.ownStart().add(0, 0, gap);
            rig.two().server().runOnServer(server -> put(server.overworld(), rig.ownPodId(), to));
            Vec3 last = rig.ownStart();
            Vec3 afterSnap = rig.ownStart();
            for (int tick = 0; tick < PUSH_TICKS; tick++) {
                context.waitTick();
                Vec3 now = onClient(context, rig.ownPodId());
                longest[i] = Math.max(longest[i], now.distanceTo(last));
                last = now;
                if (tick == SNAP_TICKS - 1) {
                    afterSnap = now;
                }
            }
            String what = "a pod pushed " + gap + " blocks";
            LOGGER.info(
                    "PodDriveSmoothClientTest: {}: longest tick {}, {} blocks short after {} ticks, {} after {}",
                    what,
                    longest[i],
                    afterSnap.distanceTo(to),
                    SNAP_TICKS,
                    last.distanceTo(to),
                    PUSH_TICKS);
            if (gap > tuning.snapGap()) {
                require(
                        afterSnap.distanceTo(to) <= SETTLE_TOLERANCE,
                        what + " should glide over within " + SNAP_TICKS + " ticks, but was " + afterSnap.distanceTo(to)
                                + " short");
            } else {
                double bound = Math.max(STEP_MARGIN * stock, gap / tuning.catchUpTicks() + CLIFF_TOLERANCE);
                require(longest[i] <= bound, what + " drew " + longest[i] + " blocks in a tick, over " + bound);
            }
            require(
                    last.distanceTo(to) <= SETTLE_TOLERANCE,
                    what + " is " + last.distanceTo(to) + " blocks short of its place after " + PUSH_TICKS + " ticks");
        }
        double justUnder = longest[1];
        double justOver = longest[2];
        require(
                Math.abs(justOver - justUnder) <= CLIFF_TOLERANCE,
                "a push of 2.01 blocks drew " + justOver + " blocks in a tick and one of 2.0 drew " + justUnder
                        + ": the catch-up has a cliff");
    }

    /**
     * A pod the server pins in place while it still reports a speed (an {@link PodEvents#AFTER_TICK} pin, a tow, {@code noPhysics} and
     * the like send no move) is drawn at most a coast past the pin, and settles on it.
     */
    private static void expectPinnedPodSettles(ClientGameTestContext context, Rig rig) {
        returnToStart(context, rig);
        double coast = coastReach() * PodTuning.DEFAULT.movement().horizontalSpeed();
        double past = 0;
        Vec3 pin;
        Vec3 drawn;
        try {
            context.getInput().holdKey(options -> options.keyUp);
            context.waitTicks(PIN_AFTER_TICKS);
            pin = serverPosition(rig.two(), rig.ownPodId());
            pinnedAt = pin;
            pinnedPodId = rig.ownPodId();
            for (int tick = 0; tick < PINNED_TICKS; tick++) {
                context.waitTick();
                past = Math.max(past, onClient(context, rig.ownPodId()).z - pin.z);
            }
            drawn = onClient(context, rig.ownPodId());
        } finally {
            pinnedAt = null;
            pinnedPodId = -1;
            context.getInput().releaseKey(options -> options.keyUp);
            // The server still acts on the last input it had for the ticks the release takes to reach it.
            context.waitTicks(INPUT_LAG_TICKS);
        }
        LOGGER.info(
                "PodDriveSmoothClientTest: a pod pinned by the server: {} blocks past the pin (at most {}), {} from it after {} ticks",
                past,
                coast + SETTLE_TOLERANCE,
                drawn.distanceTo(pin),
                PINNED_TICKS);
        require(
                past <= coast + SETTLE_TOLERANCE,
                "the drawn pod went " + past + " blocks past where the server pinned it");
        require(
                drawn.distanceTo(pin) <= SETTLE_TOLERANCE,
                "the drawn pod is " + drawn.distanceTo(pin) + " blocks from where the server pinned it");
    }

    private static Vec3 onClient(ClientGameTestContext context, int podId) {
        return context.computeOnClient(client ->
                TestEntities.require(ClientLight.clientLevel(client), podId).position());
    }

    private static boolean standsAt(Minecraft client, int podId, Vec3 at) {
        return TestEntities.require(ClientLight.clientLevel(client), podId)
                        .position()
                        .distanceToSqr(at)
                < 0.04;
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
        double backwardTolerance =
                drive.late() == null ? BACKWARD_TOLERANCE : MAX_BOUNCE_SHARE * drive.speed() / FRAMES;
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
                "PodDriveSmoothClientTest: {}: longest frame step {} (bound {}), shortest tick {} (floor {}), worst backward step {}",
                what,
                longest,
                stepBound,
                shortestTick,
                MIN_TICK_SHARE * speed,
                worstBackward);
        require(
                worstBackward >= -backwardTolerance,
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
