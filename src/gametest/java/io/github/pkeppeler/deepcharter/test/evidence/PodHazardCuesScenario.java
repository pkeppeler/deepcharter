package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.support.Inputs.JUMP;

import io.github.pkeppeler.deepcharter.ore.GasHazard;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Evidence scenario "pod-hazard-cues" (#854): an overloaded lift, a gas blast and a hard landing each name their cause. */
public class PodHazardCuesScenario extends EvidenceScenario {
    private static final int X = 5400;
    private static final int Z = 5400;
    private static final int FLOOR_Y = LAYER_ROOM_Y;
    private static final int ROOM_HEIGHT = 24;
    private static final int DROP_BLOCKS = 14;
    private static final int CUE_TICKS = 6;
    private static final int LAND_TICKS = 120;

    @Override
    protected String name() {
        return "pod-hazard-cues";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                RoomCarver.carve(
                        one,
                        new BlockPos(X - 4, FLOOR_Y - 2, Z - 4),
                        new BlockPos(X + 4, FLOOR_Y, Z + 4),
                        Blocks.STONE.defaultBlockState());
                RoomCarver.carve(
                        one,
                        new BlockPos(X - 3, FLOOR_Y + 1, Z - 3),
                        new BlockPos(X + 3, FLOOR_Y + ROOM_HEIGHT, Z + 3),
                        Blocks.AIR.defaultBlockState());
                for (int y = FLOOR_Y + 4; y < FLOOR_Y + ROOM_HEIGHT; y += 8) {
                    one.setBlock(new BlockPos(X + 4, y, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
                    one.setBlock(new BlockPos(X - 4, y, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
                }
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X + 0.5, FLOOR_Y + 1, Z + 0.5, Set.of(), 0, 0, true);
            });
            ClientWait.until(
                    context,
                    "the player in layer 1",
                    client -> client.player != null && TestLayers.clientIsIn(client, 1));
            ClientWait.lightSettled(context, singleplayer.getServer(), 1, X, Z, 4);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X + 0.5, FLOOR_Y + 1, Z + 0.5);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
                // Loaded to its engine power: the rotor has no lift to give.
                pod.setCargoMass(PodTuning.DEFAULT.movement().enginePower());
            });
            ClientWait.until(
                    context,
                    "the player riding a pod",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity);
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            context.waitTicks(10);
            screenshot(context, "hazard-cues-start");

            // Overloaded: jump does nothing, and the bar says why.
            input(singleplayer, JUMP);
            cue(context, "hazard-cues-overloaded");
            input(singleplayer, Input.EMPTY);
            singleplayer.getServer().runOnServer(server -> pod(server).setCargoMass(0f));
            context.waitTicks(60);

            // Gas: a pocket mined beside the pod blasts its hull.
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                BlockPos pocket = pod(server).blockPosition().offset(2, 0, 0);
                one.setBlock(pocket, HazardBlocks.GAS_POCKET.defaultBlockState(), 3);
                one.destroyBlock(pocket, false);
                GasHazard.vent(one, pocket);
            });
            cue(context, "hazard-cues-gas");
            context.waitTicks(60);

            // Hard landing: the pod drops from a height onto the floor. The gas left the hull part spent, so it is
            // refitted first: the landing
            // must not be the fatal one. setPos leaves onGround true until the pod's next tick, so the landing is
            // waited for by its own cost: the
            // hull drops on the tick the pod lands.
            singleplayer.getServer().runOnServer(server -> {
                PodEntity pod = pod(server);
                pod.setHull(pod.maxHull());
                pod.setDeltaMovement(0, 0, 0);
                pod.setPos(X + 0.5, FLOOR_Y + 1 + DROP_BLOCKS, Z + 0.5);
            });
            int ticks = 0;
            while (!singleplayer
                            .getServer()
                            .computeOnServer(
                                    server -> pod(server).hull() < pod(server).maxHull())
                    && ticks < LAND_TICKS) {
                context.waitTicks(1);
                ticks++;
                if (ticks % 2 == 0) {
                    frame(context);
                }
            }
            if (ticks >= LAND_TICKS) {
                throw new AssertionError("The dropped pod did not land hard within " + LAND_TICKS + " ticks");
            }
            cue(context, "hazard-cues-landing");
        }
    }

    private static PodEntity pod(MinecraftServer server) {
        return TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
    }

    private static void input(TestSingleplayerContext singleplayer, Input input) {
        singleplayer
                .getServer()
                .runOnServer(
                        server -> server.getPlayerList().getPlayers().getFirst().setLastClientInput(input));
    }

    /** Waits for the cue to reach the action bar, then records frames and a still of it. */
    private void cue(ClientGameTestContext context, String still) {
        context.waitTicks(2);
        for (int i = 0; i < CUE_TICKS; i++) {
            context.waitTicks(1);
            frame(context);
        }
        screenshot(context, still);
        frame(context);
    }
}
