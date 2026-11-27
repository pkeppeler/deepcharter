package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "pod-soft-landing" (#1432): a crewed pod is held still 7 blocks over a lit floor, then let go with
 * no keys, and the ground cushion brakes the last blocks of the sink, so the pod lands with its hull unchanged. The
 * SINK plate shows under the band while the pod sinks. On main the same drop lands at about 19 blocks a second and
 * costs hull.
 */
public class PodSoftLandingScenario extends EvidenceScenario {
    private static final int X = 5250;
    private static final int Z = 5250;
    /** Above the signal decay, so the gauges and the plate stay live. */
    private static final int FLOOR_Y = LAYER_ROOM_Y;

    private static final int HOVER_BLOCKS = 7;
    /** The least height of the hull over the floor at the release that the evidence accepts (#1432's review). */
    private static final double MIN_RELEASE_BLOCKS = 6;

    private static final int ROOM_RADIUS = 5;
    private static final int ROOM_HEIGHT = 14;
    private static final int HOVER_TICKS = 20;
    private static final int MAX_SINK_TICKS = 100;
    /**
     * The server's ticks a second while the pod sinks: a slow motion, so the recording, a few frames a second under a
     * software renderer, holds every tick of the sink and the cushion's brake.
     */
    private static final float SINK_TICK_RATE = 2f;

    private static final float NORMAL_TICK_RATE = 20f;

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
            });
            // The carve queues a chunk re-send: settle the room's light before a pod with a pilot moves in (#1303).
            ClientWait.lightSettled(context, singleplayer.getServer(), 1, X, Z, ROOM_RADIUS);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X + 0.5, FLOOR_Y + 1 + HOVER_BLOCKS, Z + 0.5, Set.of(), 0, 0, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X + 0.5, FLOOR_Y + 1 + HOVER_BLOCKS, Z + 0.5);
                // Held still at the hover height until the release, so the drop starts from rest at a known height.
                pod.setNoGravity(true);
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
            ClientWait.until(
                    context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
            // The hover: the pod hangs still with the pilot aboard and no keys held.
            tickFrames(context, HOVER_TICKS);
            screenshot(context, "soft-landing-hover");
            float hullBefore = hull(singleplayer);
            double released = singleplayer.getServer().computeOnServer(server -> {
                server.tickRateManager().setTickRate(SINK_TICK_RATE);
                PodEntity pod =
                        TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
                pod.setDeltaMovement(0, 0, 0);
                pod.setNoGravity(false);
                return pod.getBoundingBox().minY - (FLOOR_Y + 1);
            });
            if (released < MIN_RELEASE_BLOCKS) {
                throw new AssertionError("The pod should be let go at least " + MIN_RELEASE_BLOCKS
                        + " blocks over the floor, was " + released);
            }
            DeepCharter.LOGGER.info("pod-soft-landing: let go {} blocks over the floor", released);

            // Let go: the pod sinks under the SINK plate, and the cushion brakes it in the last 3 blocks.
            int ticks = 0;
            boolean gauged = false;
            while (!podOnGround(singleplayer) && ticks < MAX_SINK_TICKS) {
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
            singleplayer
                    .getServer()
                    .runOnServer(server -> server.tickRateManager().setTickRate(NORMAL_TICK_RATE));
            if (!podOnGround(singleplayer)) {
                throw new AssertionError("The pod did not land within " + MAX_SINK_TICKS + " ticks");
            }
            if (!gauged) {
                throw new AssertionError("The SINK plate never showed while the pod sank");
            }
            tickFrames(context, 10);
            float hullAfter = hull(singleplayer);
            if (hullAfter != hullBefore) {
                throw new AssertionError("A pod let go " + released
                        + " blocks over the floor should land with its hull unchanged, " + hullBefore + " before and "
                        + hullAfter + " after");
            }
            screenshot(context, "soft-landing-landed");
        }
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
