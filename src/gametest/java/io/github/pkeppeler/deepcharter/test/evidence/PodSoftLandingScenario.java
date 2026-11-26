package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.support.Inputs.JUMP;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
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

/**
 * Evidence scenario "pod-soft-landing" (#1432): a pilot hovers a pod 7 blocks over a lit floor, lets go of the rotor,
 * and the ground cushion brakes the last blocks of the sink, so the pod lands with its hull unchanged. The SINK plate
 * shows under the band while the pod sinks.
 */
public class PodSoftLandingScenario extends EvidenceScenario {
    private static final int X = 5250;
    private static final int Z = 5250;
    private static final int FLOOR_Y = TestLayers.worldY(1, LayerTuning.DEFAULT.crustThickness() + 1);
    private static final int HOVER_BLOCKS = 7;
    private static final int ROOM_RADIUS = 5;
    private static final int ROOM_HEIGHT = 14;
    private static final int HOVER_TICKS = 40;
    private static final int HOVER_TICKS_PER_FRAME = 2;
    private static final int MAX_SINK_TICKS = 100;

    @Override
    protected String name() {
        return "pod-soft-landing";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                RoomCarver.carve(
                        one,
                        new BlockPos(X - ROOM_RADIUS, FLOOR_Y - 2, Z - ROOM_RADIUS),
                        new BlockPos(X + ROOM_RADIUS, FLOOR_Y, Z + ROOM_RADIUS),
                        Blocks.STONE.defaultBlockState());
                RoomCarver.carve(
                        one,
                        new BlockPos(X - ROOM_RADIUS + 1, FLOOR_Y + 1, Z - ROOM_RADIUS + 1),
                        new BlockPos(X + ROOM_RADIUS - 1, FLOOR_Y + ROOM_HEIGHT, Z + ROOM_RADIUS - 1),
                        Blocks.AIR.defaultBlockState());
                // Glowstone in the walls lights the room, so the drop and the floor read on screen.
                for (int y = FLOOR_Y + 2; y < FLOOR_Y + ROOM_HEIGHT; y += 4) {
                    one.setBlock(new BlockPos(X + ROOM_RADIUS, y, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
                    one.setBlock(new BlockPos(X - ROOM_RADIUS, y, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
                    one.setBlock(new BlockPos(X, y, Z - ROOM_RADIUS), Blocks.GLOWSTONE.defaultBlockState(), 3);
                }
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X + 0.5, FLOOR_Y + 1 + HOVER_BLOCKS, Z + 0.5, Set.of(), 0, 0, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X + 0.5, FLOOR_Y + 1 + HOVER_BLOCKS, Z + 0.5);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the player riding a pod in layer 1",
                    client -> TestLayers.clientIsIn(client, 1)
                            && ClientSession.player(client).getVehicle() instanceof PodEntity);
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));

            // Hover: the pilot taps the rotor whenever the pod sinks.
            for (int tick = 1; tick <= HOVER_TICKS; tick++) {
                pilot(singleplayer, true);
                context.waitTicks(1);
                if (tick % HOVER_TICKS_PER_FRAME == 0) {
                    frame(context);
                }
            }
            screenshot(context, "soft-landing-hover");
            float hullBefore = hull(singleplayer);

            // Let go: the pod sinks under the SINK plate, and the cushion brakes it in the last 3 blocks.
            int ticks = 0;
            boolean gauged = false;
            while (!podOnGround(singleplayer) && ticks < MAX_SINK_TICKS) {
                pilot(singleplayer, false);
                context.waitTicks(1);
                ticks++;
                frame(context);
                if (!gauged
                        && context.computeOnClient(
                                client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                                        && CockpitHud.sinkShown(pod))) {
                    gauged = true;
                    screenshot(context, "soft-landing-sinking");
                }
            }
            if (!podOnGround(singleplayer)) {
                throw new AssertionError("The pod did not land within " + MAX_SINK_TICKS + " ticks");
            }
            if (!gauged) {
                throw new AssertionError("The SINK plate never showed while the pod sank");
            }
            for (int i = 0; i < 10; i++) {
                context.waitTicks(HOVER_TICKS_PER_FRAME);
                frame(context);
            }
            float hullAfter = hull(singleplayer);
            if (hullAfter != hullBefore) {
                throw new AssertionError("A pod let sink from a hover " + HOVER_BLOCKS
                        + " blocks up should land with its hull unchanged, " + hullBefore + " before and " + hullAfter
                        + " after");
            }
            screenshot(context, "soft-landing-landed");
        }
    }

    /** Hovering, taps the rotor while the pod sinks; otherwise gives no keys. */
    private static void pilot(TestSingleplayerContext singleplayer, boolean hover) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            boolean tap = hover && player.getVehicle() instanceof PodEntity pod && pod.getDeltaMovement().y < 0;
            player.setLastClientInput(tap ? JUMP : Input.EMPTY);
        });
    }

    private static float hull(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> TestEntities.vehicle(
                                server.getPlayerList().getPlayers().getFirst(), PodEntity.class)
                        .hull());
    }

    private static boolean podOnGround(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(
                        server -> server.getPlayerList().getPlayers().getFirst().getVehicle() instanceof PodEntity pod
                                && pod.onGround());
    }
}
