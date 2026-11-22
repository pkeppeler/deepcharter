package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Inputs.JUMP;

import io.github.pkeppeler.deepcharter.layer.LayerTuning;
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
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for #893: a pod a real client rides, left with a few hull after a gas blast, dropped 14 blocks by a server {@code setPos}
 * (the sequence of the first {@code pod-hazard-cues} scenario) lands on the floor and stays there, and its pilot stays aboard.
 */
public class PodHardLandingClientTest implements FabricClientGameTest {
    private static final int X = 5600;
    private static final int Z = 5600;
    private static final int FLOOR_Y = LayerTuning.DEFAULT.crustThickness() + 1;
    private static final int ROOM_HEIGHT = 24;
    private static final int DROP_BLOCKS = 14;
    private static final int WATCH_TICKS = 160;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
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
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X + 0.5, FLOOR_Y + 1, Z + 0.5, Set.of(), 0, 0, true);
            });
            ClientWait.until(
                    context,
                    "the player in layer 1",
                    client -> client.player != null && TestLayers.clientIsIn(client, 1));
            context.waitTicks(30);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X + 0.5, FLOOR_Y + 1, Z + 0.5);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
                pod.setCargoMass(PodTuning.DEFAULT.movement().enginePower());
            });
            ClientWait.until(
                    context,
                    "the player riding a pod",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity);
            input(singleplayer, JUMP);
            context.waitTicks(10);
            input(singleplayer, Input.EMPTY);
            singleplayer.getServer().runOnServer(server -> pod(server).setCargoMass(0f));
            context.waitTicks(60);

            // The gas pocket beside the pod empties about half its hull.
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                BlockPos pocket = pod(server).blockPosition().offset(2, 0, 0);
                one.setBlock(pocket, HazardBlocks.GAS_POCKET.defaultBlockState(), 3);
                one.destroyBlock(pocket, false);
                GasHazard.vent(one, pocket);
            });
            context.waitTicks(60);

            // The drop, as the first scenario made it: a setPos of the ridden pod, with its hull and speed as they
            // were.
            singleplayer
                    .getServer()
                    .runOnServer(server -> pod(server).setPos(X + 0.5, FLOOR_Y + 1 + DROP_BLOCKS, Z + 0.5));
            StringBuilder trace = new StringBuilder();
            double lowest = Double.MAX_VALUE;
            for (int tick = 0; tick < WATCH_TICKS; tick++) {
                context.waitTicks(1);
                double y = singleplayer
                        .getServer()
                        .computeOnServer(server -> pod(server).getY());
                lowest = Math.min(lowest, y);
                if (tick % 10 == 0) {
                    trace.append(String.format(" %d:%.1f", tick, y));
                }
            }
            if (lowest < FLOOR_Y + 1 - 0.05) {
                throw new AssertionError("the dropped pod fell below its floor (y " + (FLOOR_Y + 1) + "), lowest y "
                        + lowest + ", y by tick" + trace);
            }
            boolean aboard = singleplayer
                    .getServer()
                    .computeOnServer(server ->
                            server.getPlayerList().getPlayers().getFirst().getVehicle() instanceof PodEntity);
            if (!aboard) {
                throw new AssertionError("the pilot left the pod, y by tick" + trace);
            }
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
}
