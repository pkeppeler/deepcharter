package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD_JUMP;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodGlideTuning;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PacketJitter;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
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
 * (ADR 0082). A connection that gets two ticks slower and stays so (#1112, {@link PacketJitter#lag}) hands the moves over one a tick, each
 * stale, so the pod has coasted past every move it gets: it must go on at its speed, never turn back to the stale position. A server
 * that runs two ticks late for three ticks and catches up hands over one stale move alone and then a burst, the pattern behind CI's
 * 0.0674533 (#1112). Where a late part begins against the jitter's period changes how far the pod trails its target when the moves
 * stop, so the drives that need one start on a fixed {@link PacketJitter#phase}.
 *
 * <p>A steady drive is judged for the stall it injects, not a longer one: when the real test server also ran late and the drive saw more
 * moveless ticks than the glide covers ({@link #COVERED_MOVELESS_TICKS}), it is run again (#1168).
 */
public class PodDriveSmoothClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodDriveSmoothClientTest.class);

    /**
     * The factor every pod's horizontal speed is scaled by, from the environment (1 when unset): a faster pod, such as a sprinting one
     * (#1276), is driven through the same drives, and every bound is judged against its reported speed, not the stock one.
     */
    private static final double SPEED_SCALE = driveSpeedScale();

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

    /**
     * A connection that gets this many client ticks slower and stays so (#1112): the pod sees no move for that long, then a move a
     * tick, each as stale as the gap was long. It is what a one-tick-late move is, and it left the pod behind a stale target.
     */
    private static final int SLOWER_TICKS = 2;

    /**
     * A server that runs {@link #SLOWER_TICKS} late for this many client ticks and then catches up (#1112): the client sees no move for
     * two ticks, then one move alone, then the rest in one burst. CI's loaded runners did this, and the pod that had coasted a tick
     * read the lone move as an overshoot and stood (0.0674533 against the 0.1 floor, the same to every digit on five CI runs).
     */
    private static final int CATCH_UP_AFTER_TICKS = 3;

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

    /** The push whose arrival time is floored: the catch-up's {@code gap / catchUpTicks} term is what gets a gap this big home. */
    private static final double FLOOR_GAP = 15.0;

    /**
     * Client ticks the push of {@link #FLOOR_GAP} blocks has to arrive in. Measured 31; without the {@code gap / catchUpTicks} term
     * the pace is 0.27 a tick and it takes 57.
     */
    private static final int FLOOR_TICKS = 40;

    /** A backward teleport past the snap gap, from a pod that has just coasted: a snap, so it arrives as one does. */
    private static final double TELEPORT_BACK = 24.0;

    /** How near the drawn pod must get to the server's, which keeps driving off, in {@link #SNAP_TICKS}: measured 1.73 with the snap held, 10.9 without it. */
    private static final double DRIVING_TELEPORT_TOLERANCE = 3.0;

    /** Client ticks a held move is stalled for before the teleport arrives behind a coasting pod. */
    private static final int TELEPORT_STALL_TICKS = 4;

    /** Blocks a fast coast is offered: well over the pod's box, so an unswept destination test lands beyond the wall. */
    private static final double FAST_COAST_SPEED = 4.0;

    /** Blocks between the pod and the thin wall its fast coast heads for. */
    private static final double THIN_WALL_AHEAD = 1.6;

    /** Client ticks a fast coast is watched. */
    private static final int FAST_COAST_TICKS = 8;

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

    /**
     * The most moveless ticks a lagged drive's pod is judged for: the lag's own and the jitter's skipped tick. A connection that stays
     * slower leaves the coast's reach already used by the lag, so a server late on its own on top of it is not covered (#1112).
     */
    private static final int COVERED_LAGGED_MOVELESS_TICKS = SLOWER_TICKS + 1;

    /** The most client ticks in a row without a move that the glide covers (ADR 0082); a steady drive that saw more is not judged. */
    private static final int COVERED_MOVELESS_TICKS = 6;

    /** Runs of a steady drive before the run fails for a server that was late on its own every time. */
    private static final int DRIVE_ATTEMPTS = 3;

    private static final double BACKWARD_TOLERANCE = 1e-4;
    /** A flat drive covers at least this share of the pod's speed in every tick past the settle. */
    private static final double MIN_TICK_SHARE = 0.5;

    /** The pod an {@link PodEvents#AFTER_TICK} listener of this test holds in place, and where; none when the id is -1. */
    private static volatile int pinnedPodId = -1;

    private static volatile @Nullable Vec3 pinnedAt;

    /**
     * What a renderer drew for one view, every frame, and, every tick, how far along the drive its pod was drawn past the last position the
     * server sent for it.
     */
    private record Subject(String name, List<Vec3> frames, List<Double> leads) {
        Subject(String name) {
            this(name, new ArrayList<>(), new ArrayList<>());
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
     * its progress. A lagged one ({@link PacketJitter#lag}) holds every move for {@code stallTicks} instead, until it ends {@code
     * lagTicks} ticks in ({@code -1} for never). {@code phase} is the step of the jitter's period the late part must start on (a
     * {@link PacketJitter#phase}, {@code -1} for whichever comes at {@code stallAt}): the harness's lockstep makes each step deterministic,
     * and each gives the pod a different gap to its target when the moves stop.
     */
    private record Late(
            int stallAt,
            int stallTicks,
            int releaseAt,
            double dropBlocks,
            boolean settles,
            boolean recovers,
            boolean lags,
            int lagTicks,
            int phase) {
        Late(int stallAt, int stallTicks, int releaseAt, double dropBlocks, boolean settles, boolean recovers) {
            this(stallAt, stallTicks, releaseAt, dropBlocks, settles, recovers, false, -1, -1);
        }

        /** A stall of {@code stallTicks} in a steady drive, from the jitter's {@code phase}. */
        static Late stall(int stallTicks, int phase) {
            return new Late(STALL_AT_TICK, stallTicks, -1, 0, true, true, false, -1, phase);
        }

        /** A connection {@code lateBy} ticks slower in a steady drive, from the jitter's {@code phase}, for {@code lagTicks} ticks. */
        static Late lag(int lateBy, int lagTicks, int phase) {
            return new Late(STALL_AT_TICK, lateBy, -1, 0, true, true, true, lagTicks, phase);
        }

        boolean stops() {
            return releaseAt >= 0 || dropBlocks > 0;
        }

        /** Whether the connection stays slower to the end of the drive. */
        boolean staysSlower() {
            return lags && lagTicks < 0;
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
            double flatSpeed = movement.horizontalSpeed() * SPEED_SCALE;
            if (SPEED_SCALE != 1) {
                PodStats.MODIFY.register((pod, stats) -> stats.withHorizontalSpeed((float) flatSpeed));
            }
            // Forward and up together: the pod's speed is the two at once.
            double climbSpeed = Math.hypot(flatSpeed, movement.maxClimbSpeed());
            PodEvents.AFTER_TICK.register(pod -> {
                Vec3 at = pinnedAt;
                if (at != null && pod.getId() == pinnedPodId) {
                    pod.setPos(at);
                }
            });
            expectPushesEase(context, rig);
            expectTeleportBehindCoastSnaps(context, rig);
            expectTeleportWhileDrivingSnaps(context, rig);
            expectFastCoastStopsAtThinWall(context, rig);
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
                                Late.stall(SHORT_STALL_TICKS, -1)));
                drive(
                        context,
                        rig,
                        Drive.forward(
                                "flat drive through a server that runs late and catches up",
                                STALL_DRIVE_TICKS,
                                flatSpeed,
                                true,
                                false,
                                Late.lag(SLOWER_TICKS, CATCH_UP_AFTER_TICKS, PacketJitter.SINGLE_TICK)));
                drive(
                        context,
                        rig,
                        Drive.forward(
                                "flat drive through a connection that gets slower",
                                STALL_DRIVE_TICKS,
                                flatSpeed,
                                true,
                                false,
                                Late.lag(SLOWER_TICKS, -1, PacketJitter.SINGLE_TICK)));
                // From the double tick the stall leaves the pod no glide left to its last target: the longest moveless
                // stretch the glide is judged for, which runs it to the coast's reach (#1112).
                drive(
                        context,
                        rig,
                        Drive.forward(
                                "flat drive through a lagging server stall",
                                STALL_DRIVE_TICKS,
                                flatSpeed,
                                true,
                                false,
                                Late.stall(LAGGED_STALL_TICKS, PacketJitter.DOUBLE_TICK)));
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

    private static double driveSpeedScale() {
        String scale = System.getenv("DEEPCHARTER_DRIVE_SPEED_SCALE");
        return scale == null ? 1 : Double.parseDouble(scale);
    }

    /** The moveless ticks the {@code drive}'s pod is judged for. */
    private static int coveredMoveless(Drive drive) {
        Late late = drive.late();
        return late != null && late.staysSlower() ? COVERED_LAGGED_MOVELESS_TICKS : COVERED_MOVELESS_TICKS;
    }

    /**
     * Puts both pods on their start, behind a wall when the drive drills, holds the keys, samples every frame of the drive, and asserts
     * what the pilot's camera and the watched pod drew.
     */
    private static void drive(ClientGameTestContext context, Rig rig, Drive drive) {
        List<Integer> late = new ArrayList<>();
        for (int attempt = 1; attempt <= DRIVE_ATTEMPTS; attempt++) {
            int moveless = driveOnce(context, rig, drive);
            if (!drive.steady() || moveless <= coveredMoveless(drive)) {
                return;
            }
            late.add(moveless);
            LOGGER.info(
                    "PodDriveSmoothClientTest: {}: the server was late on its own, {} client ticks in a row without a move (the pod is judged"
                            + " up to {}); attempt {} of {}",
                    drive.name(),
                    moveless,
                    coveredMoveless(drive),
                    attempt,
                    DRIVE_ATTEMPTS);
        }
        require(
                false,
                drive.name() + ": late in all " + DRIVE_ATTEMPTS + " attempts: " + late
                        + " moveless ticks, the pod is judged up to " + coveredMoveless(drive));
    }

    /** One run of {@code drive}; returns the most client ticks in a row it saw without an entity move handled. */
    private static int driveOnce(ClientGameTestContext context, Rig rig, Drive drive) {
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
        int moveless;
        int lateFrom = -1;
        try {
            drive.keys().forEach(key -> context.getInput().holdKey(key));
            two.server().runOnServer(server -> two.mock().setInput(drive.mockInput()));
            for (int tick = 0; tick < drive.ticks(); tick++) {
                if (tick == SETTLE_TICKS) {
                    context.runOnClient(client -> PacketJitter.resetMoveless());
                }
                if (late != null && lateFrom < 0 && tick >= late.stallAt() && onPhase(context, late)) {
                    lateFrom = tick;
                    context.runOnClient(client -> {
                        if (late.lags()) {
                            PacketJitter.lag(late.stallTicks());
                        } else {
                            PacketJitter.stall(late.stallTicks());
                        }
                    });
                }
                if (late != null && late.lags() && lateFrom >= 0 && tick == lateFrom + late.lagTicks()) {
                    context.runOnClient(client -> PacketJitter.endLag());
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
                    pilot.leads().add(lead(own));
                    watched.leads().add(lead(watchedPod));
                    ownPod.leads().add(lead(own));
                    drilled[0] |= watchedPod instanceof PodEntity pod && pod.drilling();
                });
            }
            moveless = context.computeOnClient(client -> PacketJitter.movelessTicks());
        } finally {
            context.runOnClient(client -> PacketJitter.endLag());
            drive.keys().forEach(key -> context.getInput().releaseKey(key));
            two.server().runOnServer(server -> two.mock().releaseInput());
            // The server acts on the last input the client sent until the release reaches it; a pod put on its start
            // before that would drive on from the start.
            context.waitTicks(INPUT_LAG_TICKS);
        }
        boolean judgeFloor = moveless <= coveredMoveless(drive);
        if (drive.wall()) {
            require(drilled[0], "The watched pod never drilled, so the drilling drive was not a drilling drive");
        }
        if (late != null) {
            require(inside[0] == 0, drive.name() + ": a pod was drawn inside a block in " + inside[0] + " frames");
            if (late.stops()) {
                context.waitTicks(SETTLE_AFTER_TICKS);
                expectStopped(drive, late, rig, context, ownPod, watched);
                return moveless;
            }
        }
        for (Subject subject : List.of(pilot, watched)) {
            String what = drive.name() + ", " + subject.name();
            double shortestTick = expectSmooth(what, subject, drive);
            if (drive.steady() && judgeFloor) {
                require(
                        DriveJudge.reaches(shortestTick, MIN_TICK_SHARE * drive.speed()),
                        what + ": the drawn pod covered only " + shortestTick
                                + " blocks in one tick of a steady drive, under " + MIN_TICK_SHARE * drive.speed()
                                + ", after " + moveless + " moveless ticks (judged up to " + coveredMoveless(drive)
                                + ")"
                                + " (less the " + DriveJudge.POSITION_QUANTUM + " network position quantum)");
            }
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
        return moveless;
    }

    /** Whether the client tick the drive is on now starts the late part on the step of the jitter's period it asks for. */
    private static boolean onPhase(ClientGameTestContext context, Late late) {
        return late.phase() < 0
                || context.computeOnClient(client -> PacketJitter.phase(
                                ClientLight.clientLevel(client).getGameTime()))
                        == late.phase();
    }

    /** How far along the drive (south) the pod is drawn past the last position the server sent for it. */
    private static double lead(Entity pod) {
        PositionAndRotation target = Objects.requireNonNull(
                pod.getInterpolation().target(), () -> "a client pod with no glide target: " + pod);
        return pod.getZ() - target.position().z;
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
            int arrivedAt = -1;
            for (int tick = 0; tick < PUSH_TICKS; tick++) {
                context.waitTick();
                Vec3 now = onClient(context, rig.ownPodId());
                longest[i] = Math.max(longest[i], now.distanceTo(last));
                last = now;
                if (arrivedAt < 0 && now.distanceTo(to) <= SETTLE_TOLERANCE) {
                    arrivedAt = tick + 1;
                }
                if (tick == SNAP_TICKS - 1) {
                    afterSnap = now;
                }
            }
            String what = "a pod pushed " + gap + " blocks";
            LOGGER.info(
                    "PodDriveSmoothClientTest: {}: arrived after {} ticks, longest tick {}, {} blocks short after {} ticks, {} after {}",
                    what,
                    arrivedAt,
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
            if (gap == FLOOR_GAP) {
                require(
                        arrivedAt >= 0 && arrivedAt <= FLOOR_TICKS,
                        what + " arrived after " + arrivedAt + " ticks, over " + FLOOR_TICKS
                                + ": the catch-up is too slow for a big gap");
            }
        }
        double justUnder = longest[1];
        double justOver = longest[2];
        require(
                Math.abs(justOver - justUnder) <= CLIFF_TOLERANCE,
                "a push of 2.01 blocks drew " + justOver + " blocks in a tick and one of 2.0 drew " + justUnder
                        + ": the catch-up has a cliff");
    }

    /**
     * A pod that has just coasted and is then teleported more than {@code snapGap} blocks behind itself arrives as a snap does, in the
     * ticks of {@code steps} once the held moves come in: the coast's direction does not make the far move an overshoot to settle back
     * from at a share of the gap a tick.
     */
    private static void expectTeleportBehindCoastSnaps(ClientGameTestContext context, Rig rig) {
        returnToStart(context, rig);
        Vec3 to = rig.ownStart().add(0, 0, -TELEPORT_BACK);
        List<Vec3> drawn = new ArrayList<>();
        try {
            context.getInput().holdKey(options -> options.keyUp);
            context.waitTicks(PIN_AFTER_TICKS);
            context.runOnClient(client -> PacketJitter.stall(TELEPORT_STALL_TICKS));
            context.getInput().releaseKey(options -> options.keyUp);
            rig.two().server().runOnServer(server -> put(server.overworld(), rig.ownPodId(), to));
            for (int tick = 1; tick <= TELEPORT_STALL_TICKS + PUSH_TICKS; tick++) {
                context.waitTick();
                drawn.add(onClient(context, rig.ownPodId()));
            }
        } finally {
            PacketJitter.stop();
            stopDriving(context);
        }
        // The server still drives the pod on the last input it had for a tick or two after the put: the place it ends
        // on is the target.
        Vec3 end = serverPosition(rig.two(), rig.ownPodId());
        int arrivedAt = -1;
        for (int i = 0; i < drawn.size() && arrivedAt < 0; i++) {
            if (drawn.get(i).distanceTo(end) <= SETTLE_TOLERANCE) {
                arrivedAt = i + 1;
            }
        }
        LOGGER.info(
                "PodDriveSmoothClientTest: a teleport {} blocks behind a coasting pod: arrived after {} ticks (stall {}), {} short at the end",
                TELEPORT_BACK,
                arrivedAt,
                TELEPORT_STALL_TICKS,
                drawn.getLast().distanceTo(end));
        int byTick = TELEPORT_STALL_TICKS + SNAP_TICKS;
        require(
                drawn.get(byTick - 1).distanceTo(end) <= SETTLE_TOLERANCE,
                "a teleport behind a coasting pod was " + drawn.get(byTick - 1).distanceTo(end)
                        + " blocks short of its place after " + byTick + " ticks (arrived after " + arrivedAt
                        + "): it was eased, not snapped");
    }

    /**
     * A teleport of more than {@code snapGap} blocks while the pod is still being driven snaps, though the moves that follow it find the
     * pod within {@code snapGap} of its place: the snap lasts until the pod arrives. Judged against the server's own pod, which keeps
     * driving, by how near the drawn pod gets within {@link #SNAP_TICKS} ticks.
     */
    private static void expectTeleportWhileDrivingSnaps(ClientGameTestContext context, Rig rig) {
        returnToStart(context, rig);
        Vec3 to = rig.ownStart().add(0, 0, -TELEPORT_BACK);
        double nearest = Double.MAX_VALUE;
        try {
            context.getInput().holdKey(options -> options.keyUp);
            context.waitTicks(PIN_AFTER_TICKS);
            rig.two().server().runOnServer(server -> put(server.overworld(), rig.ownPodId(), to));
            for (int tick = 1; tick <= SNAP_TICKS; tick++) {
                context.waitTick();
                nearest = Math.min(
                        nearest,
                        onClient(context, rig.ownPodId()).distanceTo(serverPosition(rig.two(), rig.ownPodId())));
            }
        } finally {
            stopDriving(context);
        }
        LOGGER.info(
                "PodDriveSmoothClientTest: a teleport {} blocks behind a pod still being driven: within {} blocks of the server's pod after {} ticks (allowed {})",
                TELEPORT_BACK,
                nearest,
                SNAP_TICKS,
                DRIVING_TELEPORT_TOLERANCE);
        require(
                nearest <= DRIVING_TELEPORT_TOLERANCE,
                "a teleport behind a pod still being driven came within " + nearest + " blocks of its place in "
                        + SNAP_TICKS + " ticks, over " + DRIVING_TELEPORT_TOLERANCE + ": the snap did not last");
    }

    /**
     * A pod that is offered a coast longer than its own box, toward a wall one block thick, is drawn up to the wall and never beyond
     * it: the coast is swept, not tested at its far end only. Driven on the client's copy of the watched pod, which the server holds still.
     */
    private static void expectFastCoastStopsAtThinWall(ClientGameTestContext context, Rig rig) {
        Vec3 at = rig.mockStart();
        BlockPos wall = BlockPos.containing(at.x, at.y, at.z + THIN_WALL_AHEAD);
        double wallFrom = wall.getZ();
        double deepest = 0;
        try {
            rig.two().server().runOnServer(server -> {
                put(server.overworld(), rig.mockPodId(), at);
                setWall(server.overworld(), wall, Blocks.STONE);
            });
            ClientWait.until(
                    context,
                    "the watched pod standing at its place before the wall",
                    () -> context.computeOnClient(client -> standsAt(client, rig.mockPodId(), at)),
                    () -> "watched pod on the client " + onClient(context, rig.mockPodId()) + " for " + at);
            // The pod already stands there, so the wait above can pass before the wall's block update reaches the
            // client; a coast on a client with no wall yet draws the pod through it.
            ClientWait.until(
                    context,
                    "the wall at the pod's front on the client",
                    client ->
                            !ClientLight.clientLevel(client).getBlockState(wall).isAir());
            context.runOnClient(client -> {
                Entity pod = TestEntities.require(ClientLight.clientLevel(client), rig.mockPodId());
                pod.setDeltaMovement(0, 0, FAST_COAST_SPEED);
                pod.moveOrInterpolateTo(pod.position(), pod.getYRot(), pod.getXRot());
            });
            for (int tick = 0; tick < FAST_COAST_TICKS; tick++) {
                context.waitTick();
                deepest = Math.max(
                        deepest,
                        context.computeOnClient(
                                client -> TestEntities.require(ClientLight.clientLevel(client), rig.mockPodId())
                                        .getBoundingBox()
                                        .maxZ));
            }
        } finally {
            context.runOnClient(client -> TestEntities.require(ClientLight.clientLevel(client), rig.mockPodId())
                    .setDeltaMovement(Vec3.ZERO));
            rig.two().server().runOnServer(server -> {
                setWall(server.overworld(), wall, Blocks.AIR);
                put(server.overworld(), rig.mockPodId(), rig.mockStart());
            });
        }
        LOGGER.info(
                "PodDriveSmoothClientTest: a coast of {} blocks a tick at a wall from z {}: the pod's box reached z {}",
                FAST_COAST_SPEED,
                wallFrom,
                deepest);
        require(
                deepest <= wallFrom + SETTLE_TOLERANCE,
                "a fast coast drew the pod to z " + deepest + ", through the wall that begins at z " + wallFrom);
    }

    /** Releases the drive key and waits for the release to reach the server. */
    private static void stopDriving(ClientGameTestContext context) {
        context.getInput().releaseKey(options -> options.keyUp);
        context.waitTicks(INPUT_LAG_TICKS);
    }

    /** Fills the thin wall of {@link #expectFastCoastStopsAtThinWall} with {@code block}, centred on {@code wall}. */
    private static void setWall(ServerLevel level, BlockPos wall, Block block) {
        for (int x = -3; x <= 3; x++) {
            for (int y = 0; y < WALL_HEIGHT; y++) {
                level.setBlock(wall.offset(x, y, 0), block.defaultBlockState(), 3);
            }
        }
    }

    /**
     * A pod the server pins in place while it still reports a speed (an {@link PodEvents#AFTER_TICK} pin, a tow, {@code noPhysics} and
     * the like send no move) is drawn at most a coast past the pin, and settles on it.
     */
    private static void expectPinnedPodSettles(ClientGameTestContext context, Rig rig) {
        returnToStart(context, rig);
        double coast = coastReach() * PodTuning.DEFAULT.movement().horizontalSpeed() * SPEED_SCALE;
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
     * Asserts the drawn frames past the settle: no step backward along the drive (south, and up when the drive climbs) and no step
     * longer than {@link #STEP_MARGIN} times one frame's share of the drive's speed. Returns the shortest tick's displacement, which
     * the caller judges against {@link #MIN_TICK_SHARE} of the speed.
     *
     * <p>A tick is measured from a frame a quarter into it to the same frame of the next, so three quarters of it is the tick before. The
     * two ticks that take in a tick the pod stood at the coast's reach past the last position the server sent are not measured: ADR 0082
     * never draws the pod further past it, so a server later than the coast covers stands the pod there by design (#1112). Every other
     * tick is held to the floor.
     */
    private static double expectSmooth(String what, Subject subject, Drive drive) {
        List<Vec3> frames = subject.frames();
        double backwardTolerance =
                drive.late() == null ? BACKWARD_TOLERANCE : MAX_BOUNCE_SHARE * drive.speed() / FRAMES;
        double speed = drive.speed();
        double reach = coastReach() * speed - DriveJudge.POSITION_QUANTUM;
        boolean climbing = drive.climbing();
        double longest = 0;
        double shortestTick = Double.MAX_VALUE;
        double worstBackward = 0;
        int atReach = 0;
        double stepBound = STEP_MARGIN * speed / FRAMES;
        for (int i = SETTLE_TICKS * FRAMES + 1; i < frames.size(); i++) {
            Vec3 step = frames.get(i).subtract(frames.get(i - 1));
            longest = Math.max(longest, step.length());
            worstBackward = Math.min(worstBackward, climbing ? Math.min(step.z, step.y) : step.z);
            int tick = i / FRAMES;
            if (i % FRAMES == 0 && i >= (SETTLE_TICKS + 1) * FRAMES) {
                if (subject.leads().get(tick - 1) >= reach || subject.leads().get(tick - 2) >= reach) {
                    atReach++;
                } else {
                    shortestTick = Math.min(
                            shortestTick,
                            frames.get(i).subtract(frames.get(i - FRAMES)).horizontalDistance());
                }
            }
        }
        LOGGER.info(
                "PodDriveSmoothClientTest: {}: longest frame step {} (bound {}), shortest tick {} (floor {}; {} ticks at the coast's reach"
                        + " not measured), worst backward step {}",
                what,
                longest,
                stepBound,
                shortestTick,
                MIN_TICK_SHARE * speed,
                atReach,
                worstBackward);
        require(
                worstBackward >= -backwardTolerance,
                what + ": the drawn pod stepped backward by " + -worstBackward + " blocks in one frame");
        require(
                longest <= stepBound,
                what + ": the drawn pod stepped " + longest + " blocks in one frame, over the " + stepBound
                        + " its speed allows");
        return shortestTick;
    }
}
