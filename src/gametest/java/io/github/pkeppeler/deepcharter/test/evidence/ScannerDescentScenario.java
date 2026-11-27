package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook.Decal;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "scanner-descent" for #1272: the pilot digs a Mole straight down through a block of plain stone
 * that fills the scanner's whole view, with a shaft open above the pod as a real bore leaves it. Before #1272 every
 * frame of the scanner was the same picture; now the depth ruler scrolls up past the pod and the rock's grain moves
 * with it. Ends with a still of the scanner and a zoom of the ruler.
 */
public class ScannerDescentScenario extends EvidenceScenario {
    private static final int X = 4600;
    private static final int Z = 4600;
    private static final int TIER = 2;
    private static final ScanArea AREA = ScannerTuning.DEFAULT.area(TIER);
    /** How many slabs the pilot digs. */
    private static final int DEPTH = 10;
    /** The shaft above the pod reaches past the top of the scan, so no sky shows. */
    private static final int ABOVE = AREA.up() + 4;
    /** The stone below reaches past the bottom of the scan at the end of the dig. */
    private static final int BELOW = AREA.down() + DEPTH + 4;
    /** Blocks of stone either side of the scan plane: past the reach of a tier 2 scanner's lava look. */
    private static final int ACROSS = 3;

    private static final int TICKS_PER_FRAME = 4;
    private static final int MAX_TICKS = 3600;
    private static final int END_FRAMES = 10;
    private static final float EAST = -90f;

    @Override
    protected String name() {
        return "scanner-descent";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            int feetY = LAYER_ROOM_Y;
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                RoomCarver.carve(
                        one,
                        X - AREA.halfWidth() - 2,
                        X + AREA.halfWidth() + 2,
                        feetY - BELOW,
                        feetY + ABOVE,
                        Z - ACROSS,
                        Z + ACROSS,
                        Blocks.STONE);
                RoomCarver.carve(one, X - 1, X + 1, feetY, feetY + ABOVE, Z - 1, Z + 1, Blocks.AIR);
            });
            // The carve queues a chunk re-send: settle the room's light before a pod with a pilot moves in (#1303).
            ClientWait.lightSettled(context, singleplayer.getServer(), 1, X, Z, Math.max(AREA.halfWidth() + 2, ACROSS));
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(one, X + 0.5, feetY, Z + 0.5, Set.of(), EAST, 0f, true);
            });
            ClientWait.arrived(context, singleplayer.getServer());
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X + 0.5, feetY, Z + 0.5);
                pod.setYRot(EAST);
                pod.setFuel(100f);
                one.addFreshEntity(pod);
                ScannerPods.fit(server, player, pod, TIER);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the player riding a pod in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && TestLayers.clientIsIn(client, 1));
            context.runOnClient(client -> {
                ClientSession.player(client).setYRot(EAST);
                ClientSession.player(client).setXRot(0f);
            });
            // The charter's transmission types over the HUD after a layer is entered; the recording waits it out.
            context.waitTicks(260);
            frames(context, END_FRAMES);

            context.getInput().holdKey(options -> options.keySprint);
            int ticks = 0;
            while (podY(context) > feetY - DEPTH + 0.2 && ticks < MAX_TICKS) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
            }
            context.getInput().releaseKey(options -> options.keySprint);
            if (podY(context) > feetY - DEPTH + 0.2) {
                throw new AssertionError(
                        "the Mole did not dig " + DEPTH + " slabs down within " + MAX_TICKS + " ticks");
            }
            frames(context, END_FRAMES);
            screenshot(context, "scanner-descent");
            Decal window = context.computeOnClient(client -> CockpitHud.seated(client)
                    .orElseThrow(() -> new AssertionError("the scenario expects the cockpit's scanner window"))
                    .scanner());
            int[] box = context.computeOnClient(client -> new int[] {
                window.left(client.getWindow().getGuiScaledWidth()),
                window.top(client.getWindow().getGuiScaledHeight()),
                window.width(),
                window.height()
            });
            zoomStill(context, "scanner-descent", "scanner-descent-ruler", box[0], box[1], box[2] / 2, box[3]);
        }
    }

    private void frames(ClientGameTestContext context, int count) {
        for (int i = 0; i < count; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    private static double podY(ClientGameTestContext context) {
        return context.computeOnClient(
                client -> TestEntities.vehicle(ClientSession.player(client)).getY());
    }
}
