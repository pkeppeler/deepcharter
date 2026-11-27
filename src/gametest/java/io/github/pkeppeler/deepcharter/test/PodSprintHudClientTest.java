package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSprint;
import io.github.pkeppeler.deepcharter.test.support.ChatLines;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PodReadout;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Client GameTest for the sprint indicator (#1276): the real keys, sprint with W, light a line on the pilot's readout
 * in the theme's sprint colour, and letting go of sprint puts it out; W alone never lights it. The first sprint tells
 * the pilot once, naming their own keys. With the limp line as well, the readout stays inside the GUI and clear of
 * vanilla's rows at the two smallest GUI sizes, and the plate fits ({@link PodReadout#expectClear}).
 */
public class PodSprintHudClientTest implements FabricClientGameTest {
    private static final int FEET_Y = TestLayers.worldY(1, 10);
    private static final String SPRINT = "hud.deepcharter.pod.sprint";
    private static final int CORRIDOR = 80;
    private static final Vec3 START = new Vec3(0.5, FEET_Y, 2.5);

    /** Client ticks W alone is held: long enough for the server to have read it and moved the pod. */
    private static final int CRUISE_TICKS = 10;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                RoomCarver.carve(one, -4, 4, FEET_Y - 1, FEET_Y - 1, -2, CORRIDOR, Blocks.STONE);
                RoomCarver.carve(one, -4, 4, FEET_Y, FEET_Y + 6, -2, CORRIDOR, Blocks.AIR);
            });
            ClientWait.lightSettled(context, singleplayer.getServer(), 1, 0, CORRIDOR / 2, CORRIDOR / 2);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, START.x, START.y, START.z, Set.of(), 0f, 0f, true);
            });
            ClientWait.arrived(context, singleplayer.getServer());
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(START);
                pod.setYRot(0f);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the client riding the pod",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity);
            // The dimension change resends the player: let the ride settle before the keys go down.
            context.waitTicks(20);
            ClientWait.until(
                    context,
                    "the client still riding the pod",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity);

            wAloneLightsNothing(context, singleplayer);
            sprintWithWLightsTheLine(context);
            expectTheFirstSprintLine(context);
            context.getInput().releaseKey(options -> options.keySprint);
            ClientWait.until(
                    context,
                    "the sprint line out once sprint is let go",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && sprintLine(client, pod).isEmpty());
            context.getInput().releaseKey(options -> options.keyUp);

            // The longest readout the boost meets: a limp engine, sprinting.
            backToTheStart(singleplayer, FuelTuning.DEFAULT.soot().limpLitres());
            sprintWithWLightsTheLine(context);
            PodReadout.expectClear(context);
            context.getInput().releaseKey(options -> options.keySprint);
            context.getInput().releaseKey(options -> options.keyUp);
            backToTheStart(singleplayer, 0f);
        }
    }

    private static void wAloneLightsNothing(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        context.getInput().holdKey(options -> options.keyUp);
        for (int tick = 0; tick < CRUISE_TICKS; tick++) {
            context.waitTick();
            context.runOnClient(client -> {
                if (!(ClientSession.player(client).getVehicle() instanceof PodEntity pod)) {
                    throw new AssertionError("the player left the pod while W was held, at "
                            + ClientSession.player(client).position());
                }
                if (sprintLine(client, pod).isPresent()) {
                    throw new AssertionError("W alone lit the sprint line");
                }
            });
        }
        double moved = singleplayer
                .getServer()
                .computeOnServer(server ->
                        TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class)
                                        .getZ()
                                - START.z);
        if (moved < 1) {
            throw new AssertionError("W should drive the pod, it moved " + moved);
        }
        context.getInput().releaseKey(options -> options.keyUp);
    }

    private static void sprintWithWLightsTheLine(ClientGameTestContext context) {
        context.getInput().holdKey(options -> options.keySprint);
        context.getInput().holdKey(options -> options.keyUp);
        ClientWait.until(
                context,
                "the sprint line on the readout, in the theme's sprint colour",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                        && PodSprint.boosting(pod)
                        && sprintLine(client, pod)
                                .filter(line ->
                                        line.color() == HudLook.current().podSprintColor())
                                .isPresent());
    }

    private static void expectTheFirstSprintLine(ClientGameTestContext context) {
        ClientWait.until(
                context,
                "the one-time sprint line in chat, naming the player's sprint and forward keys",
                client -> ChatLines.received(client).stream()
                        .anyMatch(line ->
                                line.startsWith("Sprint:") && line.contains("Left Control") && line.contains("W")));
    }

    private static void backToTheStart(TestSingleplayerContext singleplayer, float soot) {
        singleplayer.getServer().runOnServer(server -> {
            PodEntity pod =
                    TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
            pod.setPos(START);
            pod.setDeltaMovement(Vec3.ZERO);
            pod.setAttached(EngineSoot.STATE, Versioned.of(new EngineSoot.State(soot, 0f, 0f)));
        });
    }

    private static Optional<PodStatusHud.Line> sprintLine(Minecraft client, PodEntity pod) {
        return PodStatusHud.readout(ClientSession.player(client), pod).stream()
                .filter(line -> line.text().getContents() instanceof TranslatableContents contents
                        && contents.getKey().equals(SPRINT))
                .findFirst();
    }
}
