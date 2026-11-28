package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.DriveJudge.coastReach;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD_JUMP;
import static io.github.pkeppeler.deepcharter.test.support.PodDrives.INPUT_LAG_TICKS;
import static io.github.pkeppeler.deepcharter.test.support.PodDrives.SETTLE_TICKS;
import static io.github.pkeppeler.deepcharter.test.support.PodDrives.SETTLE_TOLERANCE;
import static io.github.pkeppeler.deepcharter.test.support.PodDrives.STEP_MARGIN;
import static io.github.pkeppeler.deepcharter.test.support.PodDrives.onClient;
import static io.github.pkeppeler.deepcharter.test.support.PodDrives.serverPosition;
import static io.github.pkeppeler.deepcharter.test.support.PodDrives.standsAt;

import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodGlideTuning;
import io.github.pkeppeler.deepcharter.pod.PodSteeringTuning;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PacketJitter;
import io.github.pkeppeler.deepcharter.test.support.PodDrives;
import io.github.pkeppeler.deepcharter.test.support.PodDrives.Drive;
import io.github.pkeppeler.deepcharter.test.support.PodDrives.Rig;
import io.github.pkeppeler.deepcharter.test.support.PodDrives.Stall;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest for #961: a pod drives as smoothly on a client as a boat. The real client pilots one pod and
 * watches a mock player's pod beside it, over flat ground at the cruise and at the sprint's boost (#1276), climbing on
 * the rotor, and drilling into a wall, judged by {@link PodDrives}.
 *
 * <p>The harness ticks the server and client in lockstep, so the network is as regular as it never is in play. {@link PacketJitter}
 * makes it irregular the way a real connection is: every third client tick the client handles no pod move and the next tick
 * handles two. Without a slide between the server's moves the pod then stands still for a tick and lunges the next.
 *
 * <p>It also covers what a server does to a pod besides driving it (ADR 0082): a push, a teleport behind a coasting pod or one still
 * being driven, a pin while the server reports a speed, and a coast at a thin wall; and the two late-server drives where the pod
 * meets a block, a 6-tick stall across the arrival at a wall and a 5-tick stall across a landing from 10 blocks (never drawn inside
 * a block, never below the ground). The other late-server drives are {@link PodDriveLateClientTest}'s.
 */
public class PodDriveSmoothClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodDriveSmoothClientTest.class);

    private static final int FLAT_TICKS = 70;
    private static final int CLIMB_TICKS = 32;
    /** Long enough for three slabs of wall and the approach to it. */
    private static final int DRILL_TICKS = 190;

    /** A stall across the pod's arrival at the wall, which it reaches about 38 ticks into the drive. */
    private static final int WALL_STALL_AT_TICK = 34;

    private static final int WALL_STALL_TICKS = 6;

    /** A drop that lands about 10 ticks in, and a stall across the landing. */
    private static final double DROP_BLOCKS = 10;

    private static final int LANDING_STALL_AT_TICK = 17;
    private static final int LANDING_STALL_TICKS = 5;
    private static final int LANDING_DRIVE_TICKS = 50;

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

    /** Ticks a pinned pod is watched beyond the ones ADR 0082's settle needs ({@link #pinnedTicks}). */
    private static final int PIN_SLACK_TICKS = 1;

    private static final int WALL_HEIGHT = 4;

    /** The pod an {@link PodEvents#AFTER_TICK} listener of this test holds in place, and where; none when the id is -1. */
    private static volatile int pinnedPodId = -1;

    private static volatile @Nullable Vec3 pinnedAt;

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

    /** Puts the pilot's pod back on its start and waits for the client to draw it there, still. */
    private static void returnToStart(ClientGameTestContext context, Rig rig) {
        rig.two().server().runOnServer(server -> PodDrives.put(server.overworld(), rig.ownPodId(), rig.ownStart()));
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
            rig.two().server().runOnServer(server -> PodDrives.put(server.overworld(), rig.ownPodId(), to));
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
            rig.two().server().runOnServer(server -> PodDrives.put(server.overworld(), rig.ownPodId(), to));
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
            rig.two().server().runOnServer(server -> PodDrives.put(server.overworld(), rig.ownPodId(), to));
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
                PodDrives.put(server.overworld(), rig.mockPodId(), at);
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
                PodDrives.put(server.overworld(), rig.mockPodId(), rig.mockStart());
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
        double coast = coastReach() * PodTuning.DEFAULT.movement().horizontalSpeed();
        int pinnedTicks = pinnedTicks(coast);
        double past = 0;
        Vec3 pin;
        Vec3 drawn;
        try {
            context.getInput().holdKey(options -> options.keyUp);
            context.waitTicks(PIN_AFTER_TICKS);
            pin = serverPosition(rig.two(), rig.ownPodId());
            pinnedAt = pin;
            pinnedPodId = rig.ownPodId();
            for (int tick = 0; tick < pinnedTicks; tick++) {
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
                pinnedTicks);
        require(
                past <= coast + SETTLE_TOLERANCE,
                "the drawn pod went " + past + " blocks past where the server pinned it");
        require(
                drawn.distanceTo(pin) <= SETTLE_TOLERANCE,
                "the drawn pod is " + drawn.distanceTo(pin) + " blocks from where the server pinned it");
    }

    /**
     * Ticks a pod pinned {@code past} blocks behind where it is drawn is watched: the settle delay, then the ticks that giving back
     * {@code settleShare} of the gap a tick takes to bring it within {@link PodDrives#SETTLE_TOLERANCE}, and {@link #PIN_SLACK_TICKS}. A faster
     * pod coasts further past the pin, so it is watched longer (40 ticks at the stock speed).
     */
    private static int pinnedTicks(double past) {
        PodGlideTuning tuning = PodGlideTuning.DEFAULT;
        double settling = Math.log(SETTLE_TOLERANCE / past) / Math.log(1 - tuning.settleShare());
        return tuning.settleDelayTicks() + (int) Math.ceil(settling) + PIN_SLACK_TICKS;
    }
}
