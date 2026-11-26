package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;
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
 * The drives of the pod drive client tests (#961, #1041, #1112): the real client pilots one pod and watches a mock player's pod beside it.
 * Every client tick a drive samples what a renderer would draw, the pod's position at four partial ticks, for the pilot (the camera follows
 * the player riding) and for the watched pod, and asserts the drawn position never steps backward along the drive and never steps further
 * in one frame than the pod's speed allows, and that a steady drive is never still for a tick it should be moving.
 *
 * <p>A steady drive is judged for the lateness it injects, not more: when the real test server also ran late and the drive saw more
 * moveless ticks than the glide covers, it is run again (#1168).
 */
public final class PodDrives {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodDrives.class);

    /** Frames drawn per client tick: a renderer at 80 frames a second. */
    private static final float[] PARTIAL_TICKS = {0.25f, 0.5f, 0.75f, 1f};

    private static final int FRAMES = PARTIAL_TICKS.length;

    /** Client ticks after a drive starts before it is judged: the pod reaches speed and its slide fills. */
    public static final int SETTLE_TICKS = 15;

    /** Client ticks for a released key to reach the server and act there, with margin. */
    public static final int INPUT_LAG_TICKS = 5;

    /** Client ticks after a drive for a drawn pod that coasted too far to settle on the server's. */
    private static final int SETTLE_AFTER_TICKS = 40;

    /** How far the drawn pod may end from the server's, once it has settled. */
    public static final double SETTLE_TOLERANCE = 0.02;

    /** A late drive's net progress, at least this share of what the speed covers. */
    private static final double MIN_PROGRESS_SHARE = 0.8;

    /** A pod that coasted too far gives it back at under this share of its speed, so it is a bounce too small to read as a reversal. */
    private static final double MAX_BOUNCE_SHARE = 0.5;

    /** Blocks the pod's drawn box is shrunk by before it is tested against blocks: touching a wall is not inside it. */
    private static final double INSIDE_MARGIN = 0.05;

    private static final int WALL_AHEAD = 8;
    private static final int WALL_SLABS = 3;
    private static final int WALL_HEIGHT = 4;
    private static final int WALL_WIDTH = 14;

    /**
     * How far above the pod's own speed a drawn step may be: a pod that fell behind catches up at {@code PodGlideTuning.catchUp()} times
     * its speed, 1.35, and the margin leaves room for the packet rounding. An uncapped catch-up after a stall lunges 1.5 times its
     * speed or more (#1041).
     */
    public static final double STEP_MARGIN = 1.4;

    /**
     * The moveless ticks a drive whose connection stays slower is judged for beyond the lag's own: the jitter's skipped tick. A
     * connection that stays slower leaves the coast's reach already used by the lag, so a server late on its own on top of it is not
     * covered (#1112).
     */
    private static final int COVERED_LAGGED_MOVELESS_EXTRA = 1;

    /** The most client ticks in a row without a move that the glide covers (ADR 0082); a steady drive that saw more is not judged. */
    private static final int COVERED_MOVELESS_TICKS = 6;

    /** Runs of a steady drive before the run fails for a server that was late on its own every time. */
    private static final int DRIVE_ATTEMPTS = 3;

    private static final double BACKWARD_TOLERANCE = 1e-4;

    /** A steady drive covers at least this share of the pod's speed in every tick past the settle. */
    private static final double MIN_TICK_SHARE = 0.5;

    private PodDrives() {}

    /**
     * What a renderer drew for one view, every frame, and, every tick, how far along the drive (south) its pod was drawn past the last
     * position the server sent for it ({@code leads}) and that position along the drive ({@code bases}), both read from the entity's
     * position codec.
     */
    private record Subject(String name, List<Vec3> frames, List<Double> leads, List<Double> bases) {
        Subject(String name) {
            this(name, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        void sampleTick(Entity pod) {
            double base = pod.getPositionCodec().getBase().z;
            leads.add(pod.getZ() - base);
            bases.add(base);
        }

        /**
         * Whether the pod stood at {@code reach} past the last position the server sent in tick {@code tick}: it was there after the
         * tick before, and no move came in this one.
         */
        boolean stoodAtReach(int tick, double reach) {
            return leads.get(tick - 1) >= reach && bases.get(tick).equals(bases.get(tick - 1));
        }
    }

    /** The two pods the drives use: the real client's, and the mock's beside it, with where each stood at the start. */
    public record Rig(TwoPlayerServer two, int ownPodId, int mockPodId, Vec3 ownStart, Vec3 mockStart) {}

    /**
     * How a server is late in a drive (#1041, #1112): from client tick {@code at}; whether the drawn pod must settle on the server's
     * after, and whether it must keep its progress.
     */
    public sealed interface Lateness permits Stall, Lag {
        int at();

        boolean settles();

        boolean recovers();
    }

    /**
     * Every move held from client tick {@code at} for {@code ticks} ticks and handed over in one ({@link PacketJitter#stall}); the keys
     * released at client tick {@code releaseAt}, if ever; the pods dropped from {@code dropBlocks} above their start; whether the drawn
     * pod must settle on the server's, and whether it must keep its progress.
     */
    public record Stall(int at, int ticks, OptionalInt releaseAt, double dropBlocks, boolean settles, boolean recovers)
            implements Lateness {
        /** A stall in a drive that goes on: the pod must come through it with its progress, and settle after. */
        public static Stall through(int at, int ticks) {
            return new Stall(at, ticks, OptionalInt.empty(), 0, true, true);
        }

        boolean stops() {
            return releaseAt.isPresent() || dropBlocks > 0;
        }
    }

    /**
     * A connection {@code lateBy} client ticks slower from client tick {@code at} ({@link PacketJitter#lag}), until it catches up
     * {@code endsAfter} ticks later, or for the rest of the drive. The pod must keep its progress and settle after.
     */
    public record Lag(int at, int lateBy, OptionalInt endsAfter) implements Lateness {
        @Override
        public boolean settles() {
            return true;
        }

        @Override
        public boolean recovers() {
            return true;
        }
    }

    /** One kind of drive: what the real client holds, what the mock's input is, how many client ticks, and the pod's speed in it. */
    public record Drive(
            String name,
            List<Function<Options, KeyMapping>> keys,
            Input mockInput,
            int ticks,
            double speed,
            boolean climbing,
            boolean steady,
            boolean wall,
            @Nullable Lateness late) {
        /** A drive with the forward key held. */
        public static Drive forward(
                String name, int ticks, double speed, boolean steady, boolean wall, @Nullable Lateness late) {
            return new Drive(name, List.of(options -> options.keyUp), FORWARD, ticks, speed, false, steady, wall, late);
        }
    }

    /** What {@link #expectSmooth} measured: the shortest tick held to the floor, and the ticks at the coast's reach left out. */
    private record Smoothness(double shortestTick, int atReach) {}

    /**
     * Waits for the client to ride a pod with the mock's pod {@code mockPodId} in view, and returns the rig of the two, standing where
     * the server has them.
     */
    public static Rig rig(ClientGameTestContext context, TwoPlayerServer two, int mockPodId) {
        ClientWait.until(
                context,
                "the client riding a pod with the mock's pod in view",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity
                        && ClientLight.clientLevel(client).getEntity(mockPodId) instanceof PodEntity);
        int ownPodId = context.computeOnClient(
                client -> TestEntities.vehicle(ClientSession.player(client)).getId());
        return new Rig(two, ownPodId, mockPodId, serverPosition(two, ownPodId), serverPosition(two, mockPodId));
    }

    /** Releases every key a drive may hold and the mock's input. */
    public static void releaseAll(ClientGameTestContext context, Rig rig) {
        context.getInput().releaseKey(options -> options.keyUp);
        context.getInput().releaseKey(options -> options.keyJump);
        rig.two().server().runOnServer(server -> rig.two().mock().releaseInput());
    }

    /** The moveless ticks the {@code drive}'s pod is judged for. */
    private static int coveredMoveless(Drive drive) {
        return drive.late() instanceof Lag lag && lag.endsAfter().isEmpty()
                ? lag.lateBy() + COVERED_LAGGED_MOVELESS_EXTRA
                : COVERED_MOVELESS_TICKS;
    }

    /**
     * Puts both pods on their start, behind a wall when the drive drills, holds the keys, samples every frame of the drive, and asserts
     * what the pilot's camera and the watched pod drew.
     */
    // seats-pod: rides the rig pod: the pilot is seated before the first frame; call it only after
    // ClientWait.lightSettled once a carve ran
    public static void drive(ClientGameTestContext context, Rig rig, Drive drive) {
        List<Integer> late = new ArrayList<>();
        for (int attempt = 1; attempt <= DRIVE_ATTEMPTS; attempt++) {
            int moveless = driveOnce(context, rig, drive);
            if (!drive.steady() || moveless <= coveredMoveless(drive)) {
                return;
            }
            late.add(moveless);
            LOGGER.info(
                    "PodDrives: {}: the server was late on its own, {} client ticks in a row without a move (the pod is judged"
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

    /** Starts what {@code late} does to the connection at its tick. */
    private static void begin(Lateness late) {
        switch (late) {
            case Stall stall -> PacketJitter.stall(stall.ticks());
            case Lag lag -> PacketJitter.lag(lag.lateBy());
        }
    }

    /** One run of {@code drive}; returns the most client ticks in a row it saw without an entity move handled. */
    private static int driveOnce(ClientGameTestContext context, Rig rig, Drive drive) {
        TwoPlayerServer two = rig.two();
        Lateness late = drive.late();
        // A drop starts held in the air: the pods wait there without gravity, so the client has them in place before
        // they fall.
        double drop = late instanceof Stall stall ? stall.dropBlocks() : 0;
        OptionalInt releaseAt = late instanceof Stall stall ? stall.releaseAt() : OptionalInt.empty();
        OptionalInt lagEndsAt = late instanceof Lag lag && lag.endsAfter().isPresent()
                ? OptionalInt.of(lag.at() + lag.endsAfter().getAsInt())
                : OptionalInt.empty();
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
        try {
            drive.keys().forEach(key -> context.getInput().holdKey(key));
            two.server().runOnServer(server -> two.mock().setInput(drive.mockInput()));
            for (int tick = 0; tick < drive.ticks(); tick++) {
                if (tick == SETTLE_TICKS) {
                    context.runOnClient(client -> PacketJitter.resetMoveless());
                }
                if (late != null && tick == late.at()) {
                    Lateness starting = late;
                    context.runOnClient(client -> begin(starting));
                }
                if (lagEndsAt.isPresent() && tick == lagEndsAt.getAsInt()) {
                    context.runOnClient(client -> PacketJitter.endLag());
                }
                if (releaseAt.isPresent() && tick == releaseAt.getAsInt()) {
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
                    pilot.sampleTick(own);
                    watched.sampleTick(watchedPod);
                    ownPod.sampleTick(own);
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
        }
        if (late instanceof Stall stall && stall.stops()) {
            context.waitTicks(SETTLE_AFTER_TICKS);
            expectStopped(drive, stall, rig, context, ownPod, watched);
            return moveless;
        }
        for (Subject subject : List.of(pilot, watched)) {
            String what = drive.name() + ", " + subject.name();
            Smoothness smooth = expectSmooth(what, subject, drive);
            if (drive.steady() && judgeFloor) {
                require(
                        DriveJudge.reaches(smooth.shortestTick(), MIN_TICK_SHARE * drive.speed()),
                        what + ": the drawn pod covered only " + smooth.shortestTick()
                                + " blocks in one tick of a steady drive, under " + MIN_TICK_SHARE * drive.speed()
                                + ", after " + moveless + " moveless ticks (judged up to " + coveredMoveless(drive)
                                + ")"
                                + " (less the " + DriveJudge.POSITION_QUANTUM + " network position quantum)");
            }
            if (late instanceof Lag lag && lag.endsAfter().isPresent()) {
                // A server that catches up never runs the pod to the coast's reach: every tick is held to the floor.
                require(
                        smooth.atReach() == 0,
                        what + ": " + smooth.atReach()
                                + " ticks at the coast's reach, though the server caught up after " + lag.lateBy()
                                + " ticks late");
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

    /** A late drive keeps its progress: the pod covers at least {@link #MIN_PROGRESS_SHARE} of what its speed does, past the settle. */
    private static void expectProgress(String what, List<Vec3> frames, Drive drive) {
        double covered = frames.getLast().z - frames.get(SETTLE_TICKS * FRAMES).z;
        double expected = MIN_PROGRESS_SHARE * drive.speed() * (drive.ticks() - SETTLE_TICKS);
        LOGGER.info("PodDrives: {}: covered {} (at least {})", what, covered, expected);
        require(covered >= expected, what + ": the drawn pod covered only " + covered + " blocks, under " + expected);
    }

    /**
     * A drive that stops during a stall (the keys released, or the ground reached): the drawn pods never go past where the server put them
     * by more than a coast ({@link DriveJudge#coastReach} of speed; none for a landing, since a coast stops short of the ground), each frame step
     * stays under the lunge bound, the way back is under {@link #MAX_BOUNCE_SHARE} of a frame's speed, and they end on the server's.
     */
    private static void expectStopped(
            Drive drive, Stall stall, Rig rig, ClientGameTestContext context, Subject ownPod, Subject watched) {
        boolean landing = stall.dropBlocks() > 0;
        double coast = landing ? 0 : DriveJudge.coastReach() * drive.speed();
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
                    "PodDrives: {}: past the server's stop by {} (at most {}), longest frame step {} (bound {}), way back {} (at most {})",
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

    public static Vec3 onClient(ClientGameTestContext context, int podId) {
        return context.computeOnClient(client ->
                TestEntities.require(ClientLight.clientLevel(client), podId).position());
    }

    public static boolean standsAt(Minecraft client, int podId, Vec3 at) {
        return TestEntities.require(ClientLight.clientLevel(client), podId)
                        .position()
                        .distanceToSqr(at)
                < 0.04;
    }

    /** Sets the pod down at {@code at}, still, with no fall behind it. */
    public static void put(ServerLevel level, int podId, Vec3 at) {
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

    public static Vec3 serverPosition(TwoPlayerServer two, int podId) {
        return two.server()
                .computeOnServer(server ->
                        TestEntities.require(server.overworld(), podId).position());
    }

    /**
     * Asserts the drawn frames past the settle: no step backward along the drive (south, and up when the drive climbs) and no step
     * longer than {@link #STEP_MARGIN} times one frame's share of the drive's speed. Returns the shortest tick's displacement, which
     * the caller judges against {@link #MIN_TICK_SHARE} of the speed.
     *
     * <p>A tick is measured from a frame a quarter into it to the same frame of the next, so three quarters of it is the tick before. A
     * measured tick is left out of the floor only when, in it or in the tick before, the pod stood at the coast's reach past the last
     * position the server sent and no move came: ADR 0082 never draws the pod further past that position, so a server later than the
     * coast covers stands the pod there by design (#1112). A pod at the reach that a move comes to is still measured. Every other tick
     * is held to the floor.
     */
    private static Smoothness expectSmooth(String what, Subject subject, Drive drive) {
        List<Vec3> frames = subject.frames();
        double backwardTolerance =
                drive.late() == null ? BACKWARD_TOLERANCE : MAX_BOUNCE_SHARE * drive.speed() / FRAMES;
        double speed = drive.speed();
        double reach = DriveJudge.coastReach() * speed - DriveJudge.POSITION_QUANTUM;
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
            if (i % FRAMES == 0 && i >= (SETTLE_TICKS + 1) * FRAMES) {
                int tick = i / FRAMES;
                if (subject.stoodAtReach(tick, reach) || subject.stoodAtReach(tick - 1, reach)) {
                    atReach++;
                } else {
                    shortestTick = Math.min(
                            shortestTick,
                            frames.get(i).subtract(frames.get(i - FRAMES)).horizontalDistance());
                }
            }
        }
        LOGGER.info(
                "PodDrives: {}: longest frame step {} (bound {}), shortest tick {} (floor {}; {} ticks at the coast's reach"
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
        return new Smoothness(shortestTick, atReach);
    }
}
