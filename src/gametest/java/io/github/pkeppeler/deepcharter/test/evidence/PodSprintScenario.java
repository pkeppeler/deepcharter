package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD_SPRINT;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSprint;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evidence scenario "pod-sprint" (#1276, #1594): the player sprints down a lit hall with the real keys while a pilot
 * drives a pod beside them, twice. At the cruise the running player pulls away from the pod; with sprint the pod
 * pulls away from the player.
 * Then the player takes the seat and sprints: the readout shows the SPRINT line and the chat the one-time line.
 */
public class PodSprintScenario extends EvidenceScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodSprintScenario.class);

    private static final int X = 26000;
    private static final int Z = 26000;
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    private static final int HALF_WIDTH = 7;
    private static final int LENGTH = 90;
    private static final int HEIGHT = 8;
    private static final int LAMP_GAP = 6;
    private static final int TICKS_PER_FRAME = 2;
    /** Ticks of a race: the sprinting pod covers about 20 blocks of the hall. */
    private static final int RACE_TICKS = 50;

    private static final float SOUTH = 0f;
    private static final Vec3 RUNNER_START = new Vec3(X - 1.5, FLOOR_Y, Z + 3.5);
    private static final Vec3 POD_START = new Vec3(X + 2.5, FLOOR_Y, Z + 3.5);

    @Override
    protected String name() {
        return "pod-sprint";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                carveHall(one);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, RUNNER_START.x, RUNNER_START.y, RUNNER_START.z, Set.of(), SOUTH, 0f, true);
            });
            ClientWait.until(
                    context,
                    "the player in layer 1",
                    client -> client.player != null && TestLayers.clientIsIn(client, 1));
            ClientWait.lightSettled(context, singleplayer.getServer(), 1, X, Z + LENGTH / 2, LENGTH / 2);
            int podId = singleplayer.getServer().computeOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(POD_START);
                pod.setYRot(SOUTH);
                one.addFreshEntity(pod);
                return pod.getId();
            });
            MockPlayer pilot = singleplayer.getServer().computeOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                MockPlayer rider = MockPlayers.join(server, "Pilot", one, POD_START, SOUTH);
                if (!rider.player().startRiding(pod(one, podId))) {
                    throw new AssertionError("the pilot could not board the pod");
                }
                return rider;
            });
            try {
                context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                context.waitTicks(40);
                context.runOnClient(client -> client.gui.toastManager().clear());
                frame(context);
                race(context, singleplayer, pilot, podId, FORWARD, "cruise");
                race(context, singleplayer, pilot, podId, FORWARD_SPRINT, "sprint");
            } finally {
                // Off the seat first: a player who leaves the game takes the vehicle they ride with them.
                singleplayer.getServer().runOnServer(server -> {
                    pilot.player().stopRiding();
                    pilot.leave();
                });
            }
            seatAndSprint(context, singleplayer, podId);
        }
    }

    /** The player sprints with the real keys while the pilot drives with {@code input}, both from the start line. */
    private void race(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            MockPlayer pilot,
            int podId,
            Input input,
            String what) {
        toTheStartLine(context, singleplayer, podId);
        Vec3[] from = positions(singleplayer, podId);
        context.getInput().holdKey(options -> options.keySprint);
        context.getInput().holdKey(options -> options.keyUp);
        singleplayer.getServer().runOnServer(server -> pilot.setInput(input));
        for (int tick = 0; tick < RACE_TICKS; tick += TICKS_PER_FRAME) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
        Vec3[] to = positions(singleplayer, podId);
        context.getInput().releaseKey(options -> options.keyUp);
        context.getInput().releaseKey(options -> options.keySprint);
        singleplayer.getServer().runOnServer(server -> pilot.releaseInput());
        screenshot(context, "pod-sprint-" + what + "-race");
        LOGGER.info(
                "[pod-sprint] {} race: the running player covered {} blocks, the pod {}",
                what,
                to[0].z - from[0].z,
                to[1].z - from[1].z);
        boolean podAhead = to[1].z - from[1].z > to[0].z - from[0].z;
        if (podAhead != (input == FORWARD_SPRINT)) {
            throw new AssertionError(
                    "the " + what + " race should be won by the " + (input == FORWARD_SPRINT ? "pod" : "player"));
        }
        context.waitTicks(20);
    }

    /** Puts the player and the pod back on the start line, still, and waits for the client to see them there. */
    private static void toTheStartLine(ClientGameTestContext context, TestSingleplayerContext singleplayer, int podId) {
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(one, RUNNER_START.x, RUNNER_START.y, RUNNER_START.z, Set.of(), SOUTH, 0f, true);
            PodEntity pod = pod(one, podId);
            pod.setPos(POD_START);
            pod.setDeltaMovement(Vec3.ZERO);
            pod.setYRot(SOUTH);
        });
        ClientWait.until(
                context,
                "the player back on the start line",
                client -> ClientSession.player(client).position().distanceTo(RUNNER_START) < 0.1);
        context.waitTicks(20);
        context.runOnClient(client -> client.gui.toastManager().clear());
    }

    /** The running player's and the pod's positions on the server, read on one tick so a race is a fair one. */
    private static Vec3[] positions(TestSingleplayerContext singleplayer, int podId) {
        return singleplayer.getServer().computeOnServer(server -> new Vec3[] {
            server.getPlayerList().getPlayers().getFirst().position(),
            pod(TestLayers.level(server, 1), podId).position()
        });
    }

    private static PodEntity pod(ServerLevel level, int podId) {
        return TestEntities.require(level, podId, PodEntity.class);
    }

    /** The player takes the pod's seat and holds sprint with W: the readout's SPRINT line and the one-time line. */
    private void seatAndSprint(ClientGameTestContext context, TestSingleplayerContext singleplayer, int podId) {
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            PodEntity pod = pod(one, podId);
            pod.setPos(POD_START);
            pod.setYRot(SOUTH);
            player.teleportTo(one, POD_START.x, POD_START.y, POD_START.z, Set.of(), SOUTH, 0f, true);
        });
        ClientWait.arrived(context, singleplayer.getServer());
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            PodEntity pod = pod(one, podId);
            if (!player.startRiding(pod)) {
                throw new AssertionError("the player could not take the seat");
            }
        });
        ClientWait.until(
                context,
                "the player in the pod's seat",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity);
        context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        context.waitTicks(20);
        context.runOnClient(client -> client.gui.toastManager().clear());
        context.getInput().holdKey(options -> options.keySprint);
        context.getInput().holdKey(options -> options.keyUp);
        ClientWait.until(
                context,
                "the pod boosting under the player",
                client ->
                        ClientSession.player(client).getVehicle() instanceof PodEntity pod && PodSprint.boosting(pod));
        for (int i = 0; i < 6; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
        screenshot(context, "pod-sprint-hud");
        context.getInput().releaseKey(options -> options.keyUp);
        context.getInput().releaseKey(options -> options.keySprint);
    }

    /** A stone hall along +z, lit by a lamp every few blocks of the ceiling, with the runner's and the pod's lanes. */
    private static void carveHall(ServerLevel one) {
        RoomCarver.carve(
                one,
                new BlockPos(X - HALF_WIDTH, FLOOR_Y - 2, Z),
                new BlockPos(X + HALF_WIDTH, FLOOR_Y - 1, Z + LENGTH),
                Blocks.STONE.defaultBlockState());
        RoomCarver.carve(
                one,
                new BlockPos(X - HALF_WIDTH, FLOOR_Y, Z),
                new BlockPos(X + HALF_WIDTH, FLOOR_Y + HEIGHT, Z + LENGTH),
                Blocks.AIR.defaultBlockState());
        for (int dx = -HALF_WIDTH + 1; dx < HALF_WIDTH; dx += LAMP_GAP) {
            for (int dz = 1; dz < LENGTH; dz += LAMP_GAP) {
                one.setBlock(new BlockPos(X + dx, FLOOR_Y + HEIGHT, Z + dz), Blocks.GLOWSTONE.defaultBlockState(), 3);
                one.setBlock(
                        new BlockPos(X + dx, FLOOR_Y + HEIGHT - 1, Z + dz),
                        Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15),
                        3);
            }
        }
    }
}
