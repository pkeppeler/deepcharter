package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.requireNone;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodGlideTuning;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.OpenGround;
import io.github.pkeppeler.deepcharter.test.support.PodDrives;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.Arrays;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest: with server-authoritative movement the client's pod must keep up with the
 * server's, and the client must see the mock pilot's pod move.
 */
public class PodMovementClientTest implements FabricClientGameTest {
    private static final double SPEED = PodTuning.DEFAULT.movement().horizontalSpeed();

    static final double MARGIN_BLOCKS = 0.05;

    /** Input lag (SPEC section 7): 2 ticks the harness lags a pod, 1 for the client's slide (#961), at the pod's speed, plus a margin. */
    static final double MAX_LAG_BLOCKS = (2 + 1) * SPEED + MARGIN_BLOCKS;

    /** Moves in a row a loaded machine delivers a tick late, each adding a tick of speed to the trail (#1356). */
    private static final int LATE_MOVES = 2;

    /**
     * Ticks in a row the trail may stay over {@link #MAX_LAG_BLOCKS} (#1356): the {@link #LATE_MOVES} ticks the late moves are
     * missing, then the ticks the client's slide takes to bring their {@code LATE_MOVES} ticks of speed strictly under the margin
     * ({@link TrailJudge#slideTicks}). This window catches a lag that holds: a pod that trails for longer than late moves explain.
     */
    static final int MAX_TICKS_OVER =
            LATE_MOVES + TrailJudge.slideTicks(LATE_MOVES * SPEED, MARGIN_BLOCKS, PodGlideTuning.DEFAULT.steps());

    /**
     * Ticks over {@link #MAX_LAG_BLOCKS} in the whole drive, in a row or not: two independent late spells. This total catches a lag
     * that keeps coming back, dipping under the bound between spells so that no run outlasts {@link #MAX_TICKS_OVER}.
     */
    static final int MAX_TICKS_OVER_IN_ALL = 2 * MAX_TICKS_OVER;

    /**
     * The worst single tick: {@link #LATE_MOVES} ticks of speed over {@link #MAX_LAG_BLOCKS}, the most late moves add. This bound
     * catches a spike late moves cannot explain, however short.
     */
    static final double MAX_WORST_BLOCKS = MAX_LAG_BLOCKS + LATE_MOVES * SPEED;

    static final int DRIVE_TICKS = 40;
    private static final Logger LOGGER = LoggerFactory.getLogger(PodMovementClientTest.class);
    private static final double MOCK_OFFSET_BLOCKS = 8;

    /** Blocks a tick the watched pod coasts at before the push: slow, so it is still near its target when the push comes. */
    private static final double PRE_PUSH_SPEED = 0.1;

    /** Blocks a tick a push reports, as a knockback or a tow release does. */
    private static final double PUSHED_SPEED = 3.0;

    /** Blocks each move of the pushed pod advances it: under half of {@link #PUSHED_SPEED}, as a server that slows the pod faster than its last reported speed. */
    private static final double PUSHED_ADVANCE = 1.0;

    private static final int PUSHED_MOVES = 6;

    /** Blocks a pushed pod may be drawn past where the server put it: within PodGlide's arrival, so none. */
    private static final double PUSHED_PAST_TOLERANCE = 1e-4;

    /** Seat the real player in a new pod, and the mock in another pod beside it. Returns the mock pod's entity id. */
    // seats-pod: adds both pods and seats the players in them
    public static int mountBoth(TwoPlayerServer two) {
        return two.server().computeOnServer(server -> {
            ServerPlayer real = realPlayer(server, two.mock().player().getUUID());
            PodEntity realPod = spawnPod(real);
            mount(real, realPod);
            // Beside the real pod, not the real rider: a rider sits with its feet below the pod's (#382), so its
            // position is in the ground.
            two.mock().teleportTo(real.level(), realPod.position().add(MOCK_OFFSET_BLOCKS, 0, 0), real.getYRot(), 0f);
            PodEntity mockPod = spawnPod(two.mock().player());
            mount(two.mock().player(), mockPod);
            return mockPod.getId();
        });
    }

    /**
     * Moves the real player and the mock to a flat pad of generated ground far from the colony, so the drive meets nothing a world's
     * spawn happens to build. The pods are spawned at the riders, so call this before {@link #mountBoth}. Waits for the pad's chunks
     * to tick on the server and for the real client to hold the pad.
     */
    public static void moveToOpenGround(ClientGameTestContext context, TwoPlayerServer two) {
        UUID mockId = two.mock().player().getUUID();
        OpenGround.prepare(context, two.server(), player -> !player.getUUID().equals(mockId), (x, z) -> Blocks.STONE);
    }

    public static void driveMock(TwoPlayerServer two) {
        two.server().runOnServer(server -> two.mock().setInput(FORWARD));
    }

    private static ServerPlayer realPlayer(MinecraftServer server, UUID mockId) {
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> !player.getUUID().equals(mockId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the real player is not on the server"));
    }

    private static PodEntity spawnPod(ServerPlayer rider) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, rider.level());
        pod.setPos(rider.position());
        rider.level().addFreshEntity(pod);
        return pod;
    }

    private static void mount(ServerPlayer rider, PodEntity pod) {
        require(rider.startRiding(pod), "the player could not mount the new pod");
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            moveToOpenGround(context, two);
            int mockPodId = mountBoth(two);
            ClientWait.until(
                    context,
                    "the client riding the mock pod",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity
                            && ClientLight.clientLevel(client).getEntity(mockPodId) != null);
            context.waitTicks(10);
            expectPushedCoastedPodStaysBehindItsTarget(context, mockPodId);

            int ownPodId = context.computeOnClient(
                    client -> TestEntities.vehicle(ClientSession.player(client)).getId());
            Vec3 mockStart =
                    context.computeOnClient(client -> TestEntities.require(ClientLight.clientLevel(client), mockPodId)
                            .position());
            Vec3 ownStart = context.computeOnClient(
                    client -> TestEntities.vehicle(ClientSession.player(client)).position());
            context.getInput().holdKey(options -> options.keyUp);
            driveMock(two);

            double[] trail = new double[DRIVE_TICKS];
            for (int tick = 0; tick < DRIVE_TICKS; tick++) {
                context.waitTick();
                trail[tick] = lagBlocks(context, two, ownPodId);
            }
            LOGGER.info(
                    "PodMovementClientTest: client pod trailed the server pod by {} blocks at tick {}, {}: {}",
                    trail[DRIVE_TICKS - 1],
                    DRIVE_TICKS,
                    TrailJudge.summary(trail),
                    Arrays.toString(trail));
            context.getInput().releaseKey(options -> options.keyUp);

            Vec3 ownEnd = context.computeOnClient(
                    client -> TestEntities.vehicle(ClientSession.player(client)).position());
            Vec3 mockEnd =
                    context.computeOnClient(client -> TestEntities.require(ClientLight.clientLevel(client), mockPodId)
                            .position());
            require(
                    !(ownEnd.subtract(ownStart).horizontalDistance() < 2),
                    "Holding W should drive the client's own pod, it moved " + ownStart.distanceTo(ownEnd));
            require(
                    !(mockEnd.subtract(mockStart).horizontalDistance() < 2),
                    "The client should see the mock pilot's pod move, it moved " + mockStart.distanceTo(mockEnd));
            requireNone(
                    "The client's pod trailed the server's more than late moves explain (every tick: "
                            + Arrays.toString(trail) + ")",
                    TrailJudge.fault(trail));
        }
    }

    /**
     * A pod that has coasted and is then pushed (a knockback, a tow release) reports a speed its moves do not keep up with: each move
     * advances the target by less than half of it. The client draws it to each target and never dead-reckons past one at the reported
     * speed (ADR 0082). Driven on the client's copy of the watched pod, which the server holds still, as the moves a server would send.
     */
    private static void expectPushedCoastedPodStaysBehindItsTarget(ClientGameTestContext context, int podId) {
        Vec3 start = PodDrives.onClient(context, podId);
        double past = Double.NEGATIVE_INFINITY;
        try {
            context.runOnClient(client -> {
                Entity pod = TestEntities.require(ClientLight.clientLevel(client), podId);
                pod.setDeltaMovement(0, 0, PRE_PUSH_SPEED);
                pod.moveOrInterpolateTo(start, pod.getYRot(), pod.getXRot());
            });
            ClientWait.until(
                    context,
                    "the watched pod coasting past its target",
                    client -> TestEntities.require(ClientLight.clientLevel(client), podId)
                                    .getZ()
                            > start.z);
            for (int move = 1; move <= PUSHED_MOVES; move++) {
                Vec3 target = start.add(0, 0, move * PUSHED_ADVANCE);
                context.runOnClient(client -> {
                    Entity pod = TestEntities.require(ClientLight.clientLevel(client), podId);
                    pod.setDeltaMovement(0, 0, PUSHED_SPEED);
                    pod.moveOrInterpolateTo(target, pod.getYRot(), pod.getXRot());
                });
                context.waitTick();
                past = Math.max(past, PodDrives.onClient(context, podId).z - target.z);
            }
        } finally {
            context.runOnClient(client -> {
                Entity pod = TestEntities.require(ClientLight.clientLevel(client), podId);
                pod.setDeltaMovement(Vec3.ZERO);
                pod.moveOrInterpolateTo(start, pod.getYRot(), pod.getXRot());
            });
        }
        LOGGER.info(
                "PodMovementClientTest: a pushed pod that had coasted: drawn {} blocks past its target at most (allowed {})",
                past,
                PUSHED_PAST_TOLERANCE);
        require(
                past <= PUSHED_PAST_TOLERANCE,
                "a pushed pod that had coasted was drawn " + past + " blocks past where the server put it");
    }

    /** Distance between the client's pod and the server's pod now. The server side runs between two client ticks. */
    private static double lagBlocks(ClientGameTestContext context, TwoPlayerServer two, int podId) {
        Vec3 onClient = context.computeOnClient(client ->
                TestEntities.require(ClientLight.clientLevel(client), podId).position());
        Vec3 onServer = two.server().computeOnServer(server -> {
            Entity pod = TestEntities.require(server.overworld(), podId);
            require(pod != null, "the server has no pod with id " + podId);
            return pod.position();
        });
        return onClient.distanceTo(onServer);
    }
}
