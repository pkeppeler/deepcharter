package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.pod.PodChase;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "pod-controls" (#1281, #1268, #1442): the pod steers with tank controls under the chase camera that
 * the pilot seat switches to, and the drill turns it square to the grid. W drives forward, a bare D turns it a quarter
 * in place and it stops dead when D is let go, S backs up without turning, W with A turns while driving; then a
 * first-person turn, where the view turns with the pod; then W into a wall from an angle, where the pod turns to the
 * grid and bores straight.
 */
public class PodControlsScenario extends EvidenceScenario {
    private static final int X = 5800;
    private static final int Z = 5800;
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    private static final int ROOM_RADIUS = 14;
    private static final int ROOM_HEIGHT = 9;
    private static final int WALL_DEPTH = 5;
    private static final int LAMP_GAP = 5;
    private static final int TICKS_PER_FRAME = 3;
    /** A drive leg: 6 blocks at the stock 0.2 a tick, so the legs below stay inside the room. */
    private static final int LEG_TICKS = 30;
    /** A first-person drive leg, shorter: the room's west wall is near by then. */
    private static final int SHORT_LEG_TICKS = 9;
    /** A turn leg: a quarter turn at the steady 6 degrees a tick, and not a degree more once the key is let go. */
    private static final int TURN_TICKS = 15;
    /** Ticks the pod stands after a turn in place, to show the turn stopped with the key. */
    private static final int STAND_TICKS = 15;
    /** Backing up is slower than driving, and the camera sits behind the nose: a short leg keeps the pod in frame. */
    private static final int REVERSE_TICKS = 18;

    private static final int DRILL_TICKS = 360;
    private static final int SLABS_SHOWN = 2;
    /** Degrees off east the pod faces when it meets the wall. */
    private static final float OFF_THE_GRID = 35f;

    @Override
    protected String name() {
        return "pod-controls";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        PodChase.setAutomatic(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                BlockPos low = new BlockPos(X - ROOM_RADIUS, FLOOR_Y - 3, Z - ROOM_RADIUS);
                RoomCarver.carve(
                        one,
                        low,
                        new BlockPos(X + ROOM_RADIUS + WALL_DEPTH, FLOOR_Y - 1, Z + ROOM_RADIUS),
                        Blocks.STONE.defaultBlockState());
                RoomCarver.carve(
                        one,
                        new BlockPos(X - ROOM_RADIUS, FLOOR_Y, Z - ROOM_RADIUS),
                        new BlockPos(X + ROOM_RADIUS, FLOOR_Y + ROOM_HEIGHT, Z + ROOM_RADIUS),
                        Blocks.AIR.defaultBlockState());
                RoomCarver.carve(
                        one,
                        new BlockPos(X + ROOM_RADIUS + 1, FLOOR_Y, Z - ROOM_RADIUS),
                        new BlockPos(X + ROOM_RADIUS + WALL_DEPTH, FLOOR_Y + ROOM_HEIGHT, Z + ROOM_RADIUS),
                        Blocks.STONE.defaultBlockState());
                // A lit lamp every five blocks of the ceiling, so the floor and the turns read.
                for (int dx = -ROOM_RADIUS + 1; dx < ROOM_RADIUS; dx += LAMP_GAP) {
                    for (int dz = -ROOM_RADIUS + 1; dz < ROOM_RADIUS; dz += LAMP_GAP) {
                        one.setBlock(
                                new BlockPos(X + dx, FLOOR_Y + ROOM_HEIGHT, Z + dz),
                                Blocks.GLOWSTONE.defaultBlockState(),
                                3);
                        one.setBlock(
                                new BlockPos(X + dx, FLOOR_Y + ROOM_HEIGHT - 1, Z + dz),
                                Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15),
                                3);
                    }
                }
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X + 0.5, FLOOR_Y, Z - 8.5, Set.of(), 0, 0, true);
            });
            ClientWait.until(
                    context,
                    "the player in layer 1",
                    client -> client.player != null && TestLayers.clientIsIn(client, 1));
            context.waitTicks(30);
            // The directive toast for boarding is queued some ticks after arrival; let it come and go before the first
            // frame.
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));

            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X + 0.5, FLOOR_Y, Z - 8.5);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the player riding a pod",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity);
            context.waitTicks(10);
            if (context.computeOnClient(client -> client.options.getCameraType()) != CameraType.THIRD_PERSON_BACK) {
                throw new AssertionError("taking the seat did not switch to the third-person chase view");
            }
            context.runOnClient(client -> client.gui.toastManager().clear());
            screenshot(context, "pod-controls-chase-view");

            // Third person: W, then a bare D (a quarter turn in place that stops dead when D is let go, so the pod
            // stands facing west), then W, then S (backs up without turning), then W with A (a turn while driving).
            leg(context, options -> options.keyUp, null, LEG_TICKS);
            leg(context, options -> options.keyRight, null, TURN_TICKS);
            leg(context, null, null, STAND_TICKS);
            context.runOnClient(client -> client.gui.toastManager().clear());
            screenshot(context, "pod-controls-turned-in-place");
            leg(context, options -> options.keyUp, null, LEG_TICKS);
            context.runOnClient(client -> client.gui.toastManager().clear());
            screenshot(context, "pod-controls-steering-right");
            leg(context, options -> options.keyDown, null, REVERSE_TICKS);
            context.runOnClient(client -> client.gui.toastManager().clear());
            screenshot(context, "pod-controls-reversing");
            leg(context, options -> options.keyUp, options -> options.keyLeft, TURN_TICKS);
            leg(context, options -> options.keyUp, null, LEG_TICKS);

            // First person: the view turns with the pod.
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            context.waitTicks(5);
            leg(context, options -> options.keyUp, options -> options.keyRight, TURN_TICKS);
            context.runOnClient(client -> client.gui.toastManager().clear());
            screenshot(context, "pod-controls-first-person-turn");
            leg(context, options -> options.keyUp, null, SHORT_LEG_TICKS);
            leg(context, options -> options.keyUp, options -> options.keyLeft, TURN_TICKS);
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));

            // The drill: a pod facing 35 degrees off the wall drives into it and turns square before it bores.
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.vehicle(player, PodEntity.class);
                pod.teleportTo(
                        TestLayers.level(server, 1),
                        X + ROOM_RADIUS - 8.0,
                        FLOOR_Y,
                        Z + 0.5,
                        Set.of(),
                        -90f + OFF_THE_GRID,
                        0f,
                        true);
            });
            context.waitTicks(10);
            context.getInput().holdKey(options -> options.keyUp);
            int ticks = 0;
            while (ticks < DRILL_TICKS && slabsBored(singleplayer) < SLABS_SHOWN) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                clearToastsThenFrame(context);
            }
            context.getInput().releaseKey(options -> options.keyUp);
            if (slabsBored(singleplayer) < SLABS_SHOWN) {
                String state = singleplayer.getServer().computeOnServer(server -> {
                    PodEntity pod = TestEntities.vehicle(
                            server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
                    return pod.position() + " yaw " + pod.getYRot() + ", drilling " + pod.drilling() + ", fuel "
                            + pod.fuel() + ", hull " + pod.hull();
                });
                throw new AssertionError(
                        "the pod did not bore " + SLABS_SHOWN + " slabs within " + DRILL_TICKS + " ticks: " + state);
            }
            float yaw = singleplayer
                    .getServer()
                    .computeOnServer(server -> TestEntities.vehicle(
                                    server.getPlayerList().getPlayers().getFirst(), PodEntity.class)
                            .getYRot());
            if (yaw != -90f) {
                throw new AssertionError("the drill should leave the pod square to the grid, facing east, yaw " + yaw);
            }
            context.runOnClient(client -> client.gui.toastManager().clear());
            screenshot(context, "pod-controls-bored-straight");
        }
    }

    /** The directive toast of boarding slides over the frame for seconds: clear it, then record the frame. */
    private void clearToastsThenFrame(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        frame(context);
    }

    /** Holds the keys given for {@code ticks} ticks, a frame every few; with none, the pod stands. */
    private void leg(
            ClientGameTestContext context,
            @Nullable Function<Options, KeyMapping> key,
            @Nullable Function<Options, KeyMapping> turn,
            int ticks) {
        if (key != null) {
            context.getInput().holdKey(key);
        }
        if (turn != null) {
            context.getInput().holdKey(turn);
        }
        for (int done = 0; done < ticks; done += TICKS_PER_FRAME) {
            context.waitTicks(TICKS_PER_FRAME);
            clearToastsThenFrame(context);
        }
        if (key != null) {
            context.getInput().releaseKey(key);
        }
        if (turn != null) {
            context.getInput().releaseKey(turn);
        }
    }

    /** Slabs of the wall's face the drill has taken: columns of its first row that are now air. */
    private static int slabsBored(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            int bored = 0;
            for (int depth = 1; depth <= WALL_DEPTH; depth++) {
                boolean open = false;
                for (int dz = -ROOM_RADIUS; dz <= ROOM_RADIUS && !open; dz++) {
                    open = one.getBlockState(new BlockPos(X + ROOM_RADIUS + depth, FLOOR_Y, Z + dz))
                            .isAir();
                }
                if (!open) {
                    break;
                }
                bored++;
            }
            return bored;
        });
    }
}
