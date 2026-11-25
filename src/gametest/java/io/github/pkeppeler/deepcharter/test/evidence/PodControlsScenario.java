package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.pod.PodChase;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
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
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "pod-controls" (#1281, #1268): the pod steers like a boat under the chase camera that the pilot
 * seat switches to, and the drill turns it square to the grid. W drives forward, D and A turn it, S backs up without
 * turning; then a first-person turn, where the view turns with the pod; then W into a wall from an angle, where the pod
 * turns to the grid and bores straight.
 */
public class PodControlsScenario extends EvidenceScenario {
    private static final int X = 5800;
    private static final int Z = 5800;
    private static final int FLOOR_Y = TestLayers.worldY(1, LayerTuning.DEFAULT.crustThickness() + 1);
    private static final int ROOM_RADIUS = 14;
    private static final int ROOM_HEIGHT = 9;
    private static final int WALL_DEPTH = 5;
    private static final int TICKS_PER_FRAME = 3;
    private static final int LEG_TICKS = 36;
    private static final int DRILL_TICKS = 220;
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
                // A lamp in each corner, so the floor reads and the turns are easy to see.
                for (int dx : new int[] {-ROOM_RADIUS + 1, ROOM_RADIUS - 1}) {
                    for (int dz : new int[] {-ROOM_RADIUS + 1, 0, ROOM_RADIUS - 1}) {
                        one.setBlock(
                                new BlockPos(X + dx, FLOOR_Y + ROOM_HEIGHT - 1, Z + dz),
                                Blocks.GLOWSTONE.defaultBlockState(),
                                3);
                    }
                }
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X - 6.5, FLOOR_Y, Z + 0.5, Set.of(), 0, 0, true);
            });
            ClientWait.until(
                    context,
                    "the player in layer 1",
                    client -> client.player != null && TestLayers.clientIsIn(client, 1));
            context.waitTicks(30);
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));

            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X - 6.5, FLOOR_Y, Z + 0.5);
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
            screenshot(context, "pod-controls-chase-view");

            // Third person: W, then W with D, then S (backs up without turning), then W with A.
            leg(context, options -> options.keyUp, null);
            leg(context, options -> options.keyUp, options -> options.keyRight);
            screenshot(context, "pod-controls-steering-right");
            leg(context, options -> options.keyDown, null);
            screenshot(context, "pod-controls-reversing");
            leg(context, options -> options.keyUp, options -> options.keyLeft);

            // First person: the view turns with the pod.
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            context.waitTicks(5);
            leg(context, options -> options.keyUp, options -> options.keyRight);
            screenshot(context, "pod-controls-first-person-turn");
            leg(context, options -> options.keyUp, options -> options.keyLeft);
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
                frame(context);
            }
            context.getInput().releaseKey(options -> options.keyUp);
            if (slabsBored(singleplayer) < SLABS_SHOWN) {
                throw new AssertionError(
                        "the pod did not bore " + SLABS_SHOWN + " slabs within " + DRILL_TICKS + " ticks");
            }
            float yaw = singleplayer
                    .getServer()
                    .computeOnServer(server -> TestEntities.vehicle(
                                    server.getPlayerList().getPlayers().getFirst(), PodEntity.class)
                            .getYRot());
            if (yaw != -90f) {
                throw new AssertionError("the drill should leave the pod square to the grid, facing east, yaw " + yaw);
            }
            screenshot(context, "pod-controls-bored-straight");
        }
    }

    /** Holds the two keys for {@link #LEG_TICKS} ticks, a frame every few. */
    private void leg(
            ClientGameTestContext context,
            Function<Options, KeyMapping> key,
            @Nullable Function<Options, KeyMapping> turn) {
        context.getInput().holdKey(key);
        if (turn != null) {
            context.getInput().holdKey(turn);
        }
        for (int done = 0; done < LEG_TICKS; done += TICKS_PER_FRAME) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
        context.getInput().releaseKey(key);
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
