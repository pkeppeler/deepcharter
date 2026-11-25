package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.outpost.IncidentReportScreen;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.outpost.OutpostSettling;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Ledger;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Pending;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Report;
import io.github.pkeppeler.deepcharter.outpost.SettlingTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.OutpostScene;
import io.github.pkeppeler.deepcharter.test.support.OutpostScene.Scene;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.SettlingRooms;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "m2-away-settling" for #1004: a charter's outpost hall, lit, with a barrel of coal in it. The lamps go out and the pilot
 * goes home to the surface; the ledger shows two hundred minutes unattended, and the sweep takes fuel from the barrel and tells the
 * pilot "Disturbance reported at Outpost 1." in chat. Back in the hall, opening the pump shows the incident report over its screen,
 * and FILED returns to the pump.
 */
public class AwaySettlingScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int TYPING_FRAMES = 60;
    private static final int HOLD_FRAMES = 8;
    private static final float FACE_SOUTH_EAST = 315f;
    private static final float FACE_NORTH = 180f;
    private static final int UNATTENDED_WINDOWS = 200;
    private static final int BARREL_STACKS = 27;
    /** The lamps of the hall as the kit builds them, as offsets from the floor. */
    private static final List<BlockPos> LAMPS = List.of(
            new BlockPos(-2, 3, -4),
            new BlockPos(2, 3, -4),
            new BlockPos(0, 4, -4),
            new BlockPos(0, 4, 4),
            new BlockPos(-4, 4, 0),
            new BlockPos(4, 4, 0));

    /** Where the pilot stood before the hall was staged. */
    private record Home(ServerLevel level, Vec3 at, float yaw) {}

    @Override
    protected String name() {
        return "m2-away-settling";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            // Where the pilot starts, to go home to.
            Home home = singleplayer.getServer().computeOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                return new Home(player.level(), player.position(), player.getYRot());
            });
            Scene scene = singleplayer.getServer().computeOnServer(server -> {
                Scene staged = OutpostScene.stage(server);
                OutpostScene.build(server, staged);
                ServerLevel one = TestLayers.level(server, 1);
                BlockPos barrel = staged.floor().offset(3, 1, 3);
                one.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
                Container storage = SettlingRooms.containerAt(one, barrel);
                for (int slot = 0; slot < BARREL_STACKS; slot++) {
                    storage.setItem(slot, new ItemStack(Items.COAL, 64));
                }
                // The hall stays loaded for the sweep when the pilot has gone.
                // force-ok: an evidence scenario has no GameTestHelper to release through; the world is discarded when
                // it ends
                one.setChunkForced(staged.floor().getX() >> 4, staged.floor().getZ() >> 4, true);
                OutpostScene.standIn(server, staged, -1.5, -1.5, FACE_SOUTH_EAST, 20f);
                return staged;
            });
            ClientWait.until(
                    context,
                    "the hall's chunks and the charter",
                    client -> client.player != null
                            && TestLayers.clientIsIn(client, 1)
                            && ClientCharter.view().isPresent()
                            && ClientLight.clientLevel(client)
                                    .getBlockState(scene.floor().above(3))
                                    .isAir());
            context.waitTicks(40);
            clearOverlays(context);
            hold(context);
            clearOverlays(context);
            screenshot(context, "away-1-lit-hall-with-fuel");
            // The barrel's contents, so the fuel the visits will take can be seen.
            singleplayer
                    .getServer()
                    .runOnServer(server -> server.getPlayerList()
                            .getPlayers()
                            .getFirst()
                            .openMenu((MenuProvider) SettlingRooms.containerAt(
                                    TestLayers.level(server, 1), scene.floor().offset(3, 1, 3))));
            ClientWait.screen(context, ContainerScreen.class);
            hold(context);
            clearOverlays(context);
            screenshot(context, "away-1b-the-barrel-of-coal");
            context.setScreen(() -> null);

            // The lamps go out.
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                for (BlockPos lamp : LAMPS) {
                    RoomCarver.carve(
                            one,
                            scene.floor().offset(lamp),
                            scene.floor().offset(lamp),
                            Blocks.AIR.defaultBlockState());
                }
            });
            ClientWait.until(
                    context,
                    "the client to see the hall go dark",
                    client -> ClientLight.clientLevel(client)
                                    .getBrightness(
                                            net.minecraft.world.level.LightLayer.BLOCK,
                                            scene.floor().above())
                            < 3);
            context.waitTicks(20);
            hold(context);
            clearOverlays(context);
            screenshot(context, "away-2-dark-hall");

            // The pilot goes home, and the ledger shows a long time unattended. The sweep does the rest.
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(home.level(), home.at().x, home.at().y, home.at().z, Set.of(), home.yaw(), 0f, true);
                ServerLevel one = TestLayers.level(server, 1);
                long window = one.getGameTime() / SettlingTuning.DEFAULT.windowTicks();
                OutpostVisits.get(server)
                        .put(new Ledger(
                                scene.charter(),
                                1,
                                window - UNATTENDED_WINDOWS,
                                Optional.empty(),
                                Pending.NONE,
                                Report.NONE));
                OutpostSettling.settleAll(server);
            });
            ClientWait.until(
                    context, "the pilot on the surface", client -> TestLayers.clientIsIn(client, TestLayers.SURFACE));
            context.waitTicks(10);
            clearOverlays(context);
            hold(context);
            screenshot(context, "away-3-alert");

            // Back in the hall: the pump, and the report over it.
            singleplayer
                    .getServer()
                    .runOnServer(server -> OutpostScene.standIn(server, scene, 0.5, 0.5, FACE_NORTH, 5f));
            ClientWait.until(
                    context,
                    "the client back in the hall",
                    client -> client.player != null
                            && TestLayers.clientIsIn(client, 1)
                            && ClientLight.clientLevel(client)
                                    .getBlockState(scene.floor().above(3))
                                    .isAir());
            context.waitTicks(30);
            clearOverlays(context);
            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.pump())));
            ClientWait.screen(context, IncidentReportScreen.class);
            for (int i = 0; i < TYPING_FRAMES; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            hold(context);
            clearOverlays(context);
            screenshot(context, "away-4-incident-report");
            context.clickScreenButton("FILED");
            ClientWait.screen(context, FuelPumpScreen.class);
            ClientWait.until(
                    context,
                    "the pump screen finished typing",
                    client -> ClientSession.screen(client, FuelPumpScreen.class)
                            .typewriter()
                            .done());
            hold(context);
            clearOverlays(context);
            screenshot(context, "away-5-back-at-the-pump");
            context.setScreen(() -> null);
        }
    }

    /** Drops the transmission and the "Directive complete" toasts that fill the corner of a still. */
    private static void clearOverlays(ClientGameTestContext context) {
        context.runOnClient(client -> {
            TransmissionOverlay.reset();
            client.gui.toastManager().clear();
        });
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }
}
