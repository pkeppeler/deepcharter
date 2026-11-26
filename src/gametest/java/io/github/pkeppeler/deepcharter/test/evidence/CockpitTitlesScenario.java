package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook.Decal;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLoad;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "cockpit-titles" (#1436): a seated pilot's cockpit with a scanner, part of a tank, a dented hull and two einsteinium in the bay, so every
 * gauge and every number of the plate's tally shows. Stills at GUI scale 2 and 1 (an 854 x 480 window allows no larger scale), and at each a zoom of the band's
 * left half, its right half and the plate, eight times.
 */
public class CockpitTitlesScenario extends EvidenceScenario {
    private static final int X = 5700;
    private static final int Z = 5400;
    private static final int FLOOR_Y = LAYER_ROOM_Y;
    private static final int ROOM_HEIGHT = 10;
    /** The rows of the band and what hangs from its lower edge. */
    private static final int BAND_ZOOM_HEIGHT = 24;
    /** The plate's corner of the screen: under the scanner's bezel, at the right. */
    private static final int PLATE_ZOOM_WIDTH = 150;

    private static final int PLATE_ZOOM_TOP = 118;
    private static final int PLATE_ZOOM_HEIGHT = 40;

    @Override
    protected String name() {
        return "cockpit-titles";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40); // tick-wait: the new world's first ticks, as every scenario waits
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
                one.setBlock(new BlockPos(X + 4, FLOOR_Y + 4, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
                one.setBlock(new BlockPos(X - 4, FLOOR_Y + 4, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
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
                ScannerPods.fit(server, player, pod, 1);
                pod.setFuel(64f);
                pod.setHull(pod.maxHull() * 0.87f);
                for (int i = 0; i < 2; i++) {
                    pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.EINSTEINIUM));
                }
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(context, "the Ribbon over the seated pilot", CockpitHud::showsReadout);
            ClientWait.until(
                    context,
                    "the pod's bay on the client",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && PodLoad.of(pod).load() == 2 * OreType.EINSTEINIUM.mass());
            ClientWait.until(
                    context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
            // The stills show the cockpit, not the rider's arm or the dismount hint.
            EvidencePins.handsHidden = true;
            try {
                context.runOnClient(client -> client.gui.hud.setOverlayMessage(Component.empty(), false));
                context.runOnClient(client -> client.gui.toastManager().clear());
                tickFrames(context, 15);
                stills(context, 2);
                stills(context, 1);
                context.runOnClient(client -> client.options.guiScale().set(2));
            } finally {
                EvidencePins.reset();
            }
        }
    }

    /** The cockpit at GUI scale {@code scale}, and its zooms. */
    private void stills(ClientGameTestContext context, int scale) {
        context.runOnClient(client -> client.options.guiScale().set(scale));
        context.waitTicks(2); // tick-wait: the frame after the scale change lays the GUI out again
        // The first boarding completes a directive whose toast lands some ticks later, over the plate, and the
        // charter's welcome transmission covers the middle of the screen.
        context.runOnClient(client -> {
            client.gui.toastManager().clear();
            TransmissionOverlay.reset();
        });
        context.waitTicks(2); // tick-wait: a frame without the cleared toast
        String still = "cockpit-titles-gui" + scale;
        screenshot(context, still);
        int width = context.computeOnClient(client -> client.getWindow().getGuiScaledWidth());
        int height = context.computeOnClient(client -> client.getWindow().getGuiScaledHeight());
        Decal band = CockpitLook.current().ribbon().band();
        int half = band.width() / 2;
        zoomStill(context, still, still + "-zoom-left", band.left(width), band.top(height), half, BAND_ZOOM_HEIGHT);
        zoomStill(
                context,
                still,
                still + "-zoom-right",
                band.left(width) + half,
                band.top(height),
                band.width() - half,
                BAND_ZOOM_HEIGHT);
        zoomStill(
                context,
                still,
                still + "-zoom-plate",
                width - PLATE_ZOOM_WIDTH,
                PLATE_ZOOM_TOP,
                PLATE_ZOOM_WIDTH,
                PLATE_ZOOM_HEIGHT);
    }
}
