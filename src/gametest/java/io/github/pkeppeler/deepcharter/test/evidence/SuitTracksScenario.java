package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.suit.SuitGaugeHud;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.suit.SuitTracks;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.RepairStationClientTest;
import io.github.pkeppeler.deepcharter.test.RepairStationClientTest.Scene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScreenScroll;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes.Pocket;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.transmission.TransmissionCatalog;
import io.github.pkeppeler.deepcharter.transmission.TransmissionData;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "m2-suit-tracks" for #1064: the suit tracks as a player meets them. The repair station lists a row for each track,
 * buying heat tier 1 moves its row on to tier 2 and takes the price from the account, and in the unbuilt depth (where layers 3 and down
 * will ask something) a stock suit lasts 0:33 and reads how far each axis is short, while a suit with every track at tier 3 lasts 2:24.
 */
public class SuitTracksScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int HOLD_FRAMES = 8;
    private static final int TYPING_FRAMES = 45;
    private static final int OUTSIDE_X = 8000;
    private static final float FACE_NORTH = 180f;
    private static final float LOOK_SLIGHTLY_DOWN = 8f;
    private static final int SETTLE_RADIUS = 16;
    /** The first suit row: after the three repair rows and REPAIR ALL, so the stills show REPAIR ALL and the three tracks. */
    private static final int FIRST_LIST_ROW = 3;
    /** How many times a still waits for the gauge to read its target second before it takes it as it is. */
    private static final int ATTEMPTS = 6;

    @Override
    protected String name() {
        return "m2-suit-tracks";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            Scene scene = singleplayer.getServer().computeOnServer(RepairStationClientTest::setUp);
            // The breach and the zones on the way down pay Personnel's bonuses when the scenario drops into the unbuilt
            // depth. Every transmission with a bonus is marked fired now, so the balance the stills show stays the one the
            // scenario leaves.
            singleplayer
                    .getServer()
                    .runOnServer(server -> TransmissionCatalog.all().stream()
                            .filter(transmission -> transmission.bonus().isPresent())
                            .forEach(transmission ->
                                    TransmissionData.get(server).fire(scene.charter().id(), transmission)));
            ClientWait.until(
                    context,
                    "the charter's balance",
                    client -> client.player != null && ClientCharter.view().isPresent());
            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.station())));
            ClientWait.screen(context, RepairStationScreen.class);
            typeOut(context);
            scrollSuitRowsIntoView(context);
            hold(context);
            screenshot(context, "suit-tracks-1-repair-station-rows");

            RepairStationClientTest.clickRow(context, "SUIT HEAT T1 $450");
            ClientWait.until(
                    context,
                    "heat tier 1 fitted",
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(
                                    server -> SuitScenes.tracks(first(server)).heat() == 1),
                    () -> "heat tier 1 not fitted yet");
            // The server's answer rebuilds the list; let it land before the still.
            context.waitTicks(TICKS_PER_FRAME);
            scrollSuitRowsIntoView(context);
            hold(context);
            screenshot(context, "suit-tracks-2-heat-tier-1-bought");
            context.setScreen(() -> null);

            // A stock suit in the unbuilt depth: 180 suit-seconds at 5.5 a second is 0:33, short 5 on heat and
            // pressure.
            singleplayer.getServer().runOnServer(server -> {
                first(server).setPermanentlyInvulnerable(true);
                SuitScenes.setTracks(first(server), SuitTracks.STOCK);
                stand(server);
            });
            stillReading(context, singleplayer, "SUIT 0:33", 185.5f, "suit-tracks-3-stock-suit-unbuilt-depth");

            // Every track at tier 3: 360 suit-seconds at 2.5 a second is 2:24.
            singleplayer
                    .getServer()
                    .runOnServer(server -> SuitScenes.setTracks(first(server), new SuitTracks(3, 3, 3)));
            stillReading(context, singleplayer, "SUIT 2:24", 362.5f, "suit-tracks-4-all-tier-3-unbuilt-depth");
        }
    }

    private static ServerPlayer first(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static void stand(MinecraftServer server) {
        ServerLevel level = TestLayers.level(server, 1);
        Pocket pocket = SuitScenes.unbuilt(level, OUTSIDE_X);
        Vec3 stand = pocket.stand();
        first(server).teleportTo(level, stand.x, stand.y, stand.z, Set.of(), FACE_NORTH, LOOK_SLIGHTLY_DOWN, true);
    }

    /**
     * Takes {@code still} on the second the gauge reads {@code label}: the reserve is set a little over what the label needs so that the
     * next real second brings it to the label, and the still waits for it. A second later the label moves on, so the wait is retried.
     */
    private void stillReading(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            String label,
            float reserve,
            String still) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            singleplayer.getServer().runOnServer(server -> SuitScenes.setReserve(first(server), reserve, true));
            ClientWait.until(
                    context,
                    "the gauge reading " + label,
                    client -> SuitGaugeHud.gauge(client)
                            .filter(gauge -> gauge.label().getString().equals(label))
                            .isPresent());
            settle(context);
            boolean stillReads = context.computeOnClient(client -> SuitGaugeHud.gauge(client)
                    .filter(gauge -> gauge.label().getString().equals(label))
                    .isPresent());
            if (stillReads || attempt == ATTEMPTS - 1) {
                screenshot(context, still);
                return;
            }
        }
    }

    private void typeOut(ClientGameTestContext context) {
        for (int i = 0; i < TYPING_FRAMES; i++) {
            boolean done = context.computeOnClient(
                    client -> ClientSession.screen(client, Screen.class) instanceof TerminalViewScreen view
                            && view.typewriter().done());
            if (done) {
                break;
            }
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    private static void scrollSuitRowsIntoView(ClientGameTestContext context) {
        context.runOnClient(client ->
                ScreenScroll.scrollTo(ClientSession.screen(client, RepairStationScreen.class), FIRST_LIST_ROW));
    }

    private static void settle(ClientGameTestContext context) {
        context.runOnClient(client -> {
            TransmissionOverlay.reset();
            client.gui.toastManager().clear();
        });
        var at = context.computeOnClient(client -> ClientSession.player(client).blockPosition());
        ClientWait.chunksAround(context, at.getX(), at.getZ(), SETTLE_RADIUS);
        ClientWait.until(
                context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }
}
