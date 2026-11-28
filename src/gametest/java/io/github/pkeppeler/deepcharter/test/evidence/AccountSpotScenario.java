package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.market.AccountHud;
import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Evidence scenario "account-spot" for #1642 and #1604: the charter's account line on foot and in the pilot's seat, at
 * GUI scale 2 (the 854 x 480 window) and GUI scale 4 (a 1920 x 1080 window, where the Ribbon still fits), in a lit
 * stone chamber in layer 1. The four stills {@code account-spot-foot-gui2}, {@code account-spot-seated-gui2},
 * {@code account-spot-seated-gui4} and {@code account-spot-foot-gui4} show the line in one spot at the left edge, and
 * the altimeter, the plate's depth and the scanner's ruler in metres. The GUI scale 4 stills are of another window
 * size, so they go to the client's screenshot folder, build/run/clientGameTest/screenshots.
 */
public class AccountSpotScenario extends EvidenceScenario {
    private static final int X = 6400;
    private static final int Z = 6400;
    private static final int FLOOR_Y = LAYER_ROOM_Y;
    /** Facing north, at the wall the lamps are on. */
    private static final float FACING_NORTH = 180f;

    private static final float EYE_PITCH = 4f;
    private static final int GUI_SCALE = 2;
    private static final int BIG_GUI_SCALE = 4;
    private static final int BIG_WINDOW_WIDTH = 1920;
    private static final int BIG_WINDOW_HEIGHT = 1080;
    private static final int WINDOW_SETTLE_TICKS = 20;
    private static final int TRANSMISSION_TICKS = 60;
    private static final int SETTLE_TICKS = 25;
    private static final long BALANCE = 12_345;

    /** The pod beside the pilot, made in {@link #stage}. */
    private final UUID[] pod = new UUID[1];

    @Override
    protected String name() {
        return "account-spot";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            EvidenceWorld.pin(context, singleplayer);
            context.runOnClient(client -> {
                client.options.guiScale().set(GUI_SCALE);
                client.options.setCameraType(CameraType.FIRST_PERSON);
            });
            EvidencePins.lightFlickerOff = true;
            EvidencePins.handsHidden = true;
            try {
                stage(context, singleplayer);
                settle(context);
                screenshot(context, "account-spot-foot-gui2");
                ride(context, singleplayer, true);
                settle(context);
                screenshot(context, "account-spot-seated-gui2");
                context.runOnClient(client -> client.getWindow().setWindowed(BIG_WINDOW_WIDTH, BIG_WINDOW_HEIGHT));
                context.waitTicks(WINDOW_SETTLE_TICKS); // tick-wait: the window resizes over a few frames
                try {
                    context.runOnClient(client -> client.getWindow().setGuiScale(BIG_GUI_SCALE));
                    settle(context);
                    if (!context.computeOnClient(
                            client -> CockpitHud.seated(client).isPresent())) {
                        throw new AssertionError("the Ribbon should fit a 1920 x 1080 window at GUI scale 4");
                    }
                    context.takeScreenshot("account-spot-seated-gui4");
                    ride(context, singleplayer, false);
                    settle(context);
                    context.takeScreenshot("account-spot-foot-gui4");
                } finally {
                    context.runOnClient(client -> client.getWindow().setWindowed(854, 480));
                    context.waitTicks(WINDOW_SETTLE_TICKS); // tick-wait: the window resizes back
                    context.runOnClient(client -> client.getWindow().setGuiScale(GUI_SCALE));
                }
            } finally {
                EvidencePins.reset();
            }
        }
    }

    /** The chamber, the pilot standing in it beside a pod with a tier 1 scanner, and the charter's account funded. */
    private void stage(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            stoneChamber(
                    one,
                    X,
                    Z,
                    FLOOR_Y,
                    new BlockPos(X - 3, FLOOR_Y + 4, Z - 5),
                    new BlockPos(X + 3, FLOOR_Y + 4, Z - 5),
                    new BlockPos(X - 5, FLOOR_Y + 4, Z),
                    new BlockPos(X + 5, FLOOR_Y + 4, Z));
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(one, X + 0.5, FLOOR_Y, Z + 2.5, Set.of(), FACING_NORTH, 0f, true);
        });
        ClientWait.arrived(context, singleplayer.getServer());
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            PodEntity pod = TestEntities.create(PodRegistry.POD, one);
            pod.setPos(X + 0.5, FLOOR_Y, Z + 0.5);
            pod.setYRot(FACING_NORTH);
            one.addFreshEntity(pod);
            ScannerPods.fit(server, player, pod, 1);
            this.pod[0] = pod.getUUID();
            Charters.readableCharterOf(server, player.getUUID())
                    .ifPresent(charter -> Charters.deposit(server, charter.id(), BALANCE));
        });
        ClientWait.until(
                context,
                "the charter's account on the client",
                client -> AccountHud.text().isPresent() && TestLayers.clientIsIn(client, 1));
        context.waitTicks(
                TRANSMISSION_TICKS); // tick-wait: a charter's first transmission arrives a moment after founding, and
        // every shot clears it
    }

    /** Seats the pilot in the pod, or stands them up beside it, and waits for the client to agree. */
    private void ride(ClientGameTestContext context, TestSingleplayerContext singleplayer, boolean seated) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            if (seated) {
                PodEntity seat = TestEntities.require(TestLayers.level(server, 1), this.pod[0], PodEntity.class);
                if (!player.startRiding(seat)) {
                    throw new AssertionError("the pilot could not board the pod");
                }
            } else {
                player.stopRiding();
                player.teleportTo(X + 0.5, FLOOR_Y, Z + 2.5);
            }
        });
        ClientWait.until(
                context,
                seated ? "the pilot seated" : "the pilot on foot",
                client -> (ClientSession.player(client).getVehicle() instanceof PodEntity) == seated);
    }

    /** The view level and the popups cleared, then a wait until the view has rendered and the gauges have eased. */
    private static void settle(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ClientSession.player(client).setYRot(FACING_NORTH);
            ClientSession.player(client).setXRot(EYE_PITCH);
            ClientSession.player(client).xRotO = EYE_PITCH;
            client.gui.toastManager().clear();
            TransmissionOverlay.reset();
        });
        ClientWait.until(
                context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
        context.waitTicks(SETTLE_TICKS); // tick-wait: the scanner rescans, the gauges ease and the pod settles
    }
}
