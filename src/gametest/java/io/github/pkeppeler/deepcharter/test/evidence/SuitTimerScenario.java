package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.client.suit.SuitGaugeHud;
import io.github.pkeppeler.deepcharter.client.suit.SuitGaugeHud.Tone;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.outpost.OutpostData;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.OutpostScene;
import io.github.pkeppeler.deepcharter.test.support.OutpostScene.Scene;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes.Pocket;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "m2-suit-timer" for #993: the suit timer as a player meets it. In a sound hall of layer 1 (which asks nothing) the suit
 * refills; layer 3 has no rows yet, so the unbuilt depth stands in for it, where nothing is rated. There a hall of plates is uninhabitable:
 * the suit runs at half inside, the first drain says what is short, the gauge turns amber and red, the pump's and the processor's
 * screens carry the risk line; outside, on foot, the gauge runs at the full rate and the suit breaches at zero.
 */
public class SuitTimerScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int HOLD_FRAMES = 8;
    private static final int TYPING_FRAMES = 45;
    private static final int HALL_X = 7800;
    private static final int HALL_Z = 7800;
    private static final int OUTSIDE_X = 7900;
    private static final float FACE_NORTH = 180f;
    private static final float LOOK_SLIGHTLY_DOWN = 8f;
    /** Blocks round the player whose chunks the client must hold before a still: the hall's stone shell and a little more. */
    private static final int SETTLE_RADIUS = 16;

    @Override
    protected String name() {
        return "m2-suit-timer";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            Scene scene = singleplayer.getServer().computeOnServer(OutpostScene::stage);
            ClientWait.until(
                    context,
                    "the player in layer 1 with the charter's balance",
                    client -> client.player != null
                            && TestLayers.clientIsIn(client, 1)
                            && ClientCharter.view().isPresent());

            // A sound hall: layer 1 asks nothing, so the suit refills there.
            singleplayer.getServer().runOnServer(server -> {
                OutpostScene.build(server, scene);
                OutpostScene.standIn(server, scene, 0.5, 2.5, FACE_NORTH, LOOK_SLIGHTLY_DOWN);
                SuitScenes.setReserve(first(server), 30f, true);
            });
            ClientWait.until(
                    context,
                    "the suit refilling in the hall",
                    client -> SuitGaugeHud.gauge(client)
                            .filter(gauge -> gauge.label().getString().equals("SUIT REFILLING"))
                            .isPresent());
            settle(context);
            hold(context);
            screenshot(context, "suit-1-refilling-in-a-sound-hall");

            // An uninhabitable hall in the unbuilt depth; the first drain says what is short once the hall is drawn.
            BlockPos hallFloor = SuitScenes.unbuiltHallFloor(HALL_X, HALL_Z);
            CharterId charter = scene.charter();
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel level = TestLayers.level(server, 1);
                SuitScenes.unbuiltHall(level, hallFloor);
                RepairState repairs = RepairState.get(server);
                for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
                    type.parts().forEach(part -> repairs.insert(type, part));
                }
                // world-data: the scenario's world is its own, and the hall is a record the kit cannot make here
                server.getDataStorage()
                        .set(
                                OutpostData.TYPE,
                                SuitScenes.dataWith(new Outpost(charter, 3, level.dimension(), hallFloor)));
                ServerPlayer player = first(server);
                player.setPermanentlyInvulnerable(true);
                // Said already, so that the first line waits until the hall is drawn.
                SuitScenes.setReserve(player, 180f, true);
                // The middle of the floor: the pump and the processor on the north wall are within the server's
                // terminal reach.
                player.teleportTo(
                        level,
                        hallFloor.getX() + 0.5,
                        hallFloor.getY() + 1,
                        hallFloor.getZ() + 0.5,
                        Set.of(),
                        FACE_NORTH,
                        LOOK_SLIGHTLY_DOWN,
                        true);
            });
            ClientWait.until(
                    context,
                    "the suit leaking in the hall of the unbuilt depth",
                    client -> client.player != null
                            && SuitGaugeHud.gauge(client)
                                    .filter(gauge -> gauge.label().getString().startsWith("SUIT 1:"))
                                    .isPresent());
            settle(context);
            // The action bar fades in about three seconds, so the first line is said only now.
            singleplayer.getServer().runOnServer(server -> SuitScenes.setReserve(first(server), 180f, false));
            ClientWait.until(
                    context,
                    "the suit's first line said",
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(
                                    server -> SuitScenes.state(first(server)).explained()),
                    () -> "not said yet");
            hold(context);
            screenshot(context, "suit-2-leaking-hall-first-line");

            singleplayer.getServer().runOnServer(server -> SuitScenes.setReserve(first(server), 70f, true));
            ClientWait.until(
                    context,
                    "the gauge amber",
                    client -> SuitGaugeHud.gauge(client)
                            .filter(gauge -> gauge.tone() == Tone.AMBER)
                            .isPresent());
            hold(context);
            screenshot(context, "suit-3-leaking-hall-amber");

            openTerminal(context, hallFloor.offset(-2, 1, -4), FuelPumpScreen.class);
            screenshot(context, "suit-4-pump-risk-line");
            context.setScreen(() -> null);
            openTerminal(context, hallFloor.offset(2, 1, -4), OreProcessorScreen.class);
            screenshot(context, "suit-5-processor-risk-line");
            context.setScreen(() -> null);

            // Outside, on foot: the full rate, then the breach.
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel level = TestLayers.level(server, 1);
                Pocket pocket = SuitScenes.unbuilt(level, OUTSIDE_X);
                ServerPlayer player = first(server);
                SuitScenes.setReserve(player, 80f, true);
                Vec3 stand = pocket.stand();
                player.teleportTo(level, stand.x, stand.y, stand.z, Set.of(), FACE_NORTH, LOOK_SLIGHTLY_DOWN, true);
            });
            ClientWait.until(
                    context,
                    "the suit exposed outside the hall",
                    client -> SuitGaugeHud.gauge(client)
                            .filter(gauge -> gauge.tone() != Tone.OK
                                    && gauge.axes().getString().contains("HEAT"))
                            .isPresent());
            settle(context);
            hold(context);
            screenshot(context, "suit-6-exposed-outside");
            singleplayer.getServer().runOnServer(server -> SuitScenes.setReserve(first(server), 6f, true));
            ClientWait.until(
                    context,
                    "the suit breached",
                    client -> SuitGaugeHud.gauge(client)
                            .filter(gauge -> gauge.label().getString().equals("SUIT BREACHED"))
                            .isPresent());
            hold(context);
            screenshot(context, "suit-7-breached");
        }
    }

    private static ServerPlayer first(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private void openTerminal(ClientGameTestContext context, BlockPos at, Class<? extends Screen> screen) {
        context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(at)));
        ClientWait.screen(context, screen);
        for (int i = 0; i < TYPING_FRAMES; i++) {
            boolean done = context.computeOnClient(client -> {
                return ClientSession.screen(client, Screen.class) instanceof TerminalViewScreen view
                        && view.typewriter().done();
            });
            if (done) {
                break;
            }
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
        hold(context);
    }

    /**
     * Clears the transmissions and toasts, and waits until the client holds the chunks round the player and has meshed every
     * section in view: the gauge reads the server's state, so it shows before a place just teleported to is drawn.
     */
    private static void settle(ClientGameTestContext context) {
        context.runOnClient(client -> {
            TransmissionOverlay.reset();
            client.gui.toastManager().clear();
        });
        BlockPos at =
                context.computeOnClient(client -> ClientSession.player(client).blockPosition());
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
