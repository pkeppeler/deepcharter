package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.suit.SuitGaugeHud;
import io.github.pkeppeler.deepcharter.client.suit.SuitGaugeHud.Gauge;
import io.github.pkeppeler.deepcharter.client.suit.SuitGaugeHud.Tone;
import io.github.pkeppeler.deepcharter.client.theme.SuitLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.outpost.RatingAxis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitState;
import io.github.pkeppeler.deepcharter.suit.SuitState.Mode;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.PlacementStage;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Client GameTest for the suit gauge (#993): how it reads for each state of the suit (words, colour, blinking), that it shows nothing where
 * the suit is inert, in a pod's seat and for a creative player, that a suit log this build cannot read says so in red, that the one
 * rectangle {@link HudKeepClear} gives for it is the box its text is drawn in, and that it fits the narrowest GUI. The live part runs in
 * the unbuilt depth, where the real suit drains, so it reads what the client holds and not fixed numbers.
 */
public class SuitGaugeClientTest implements FabricClientGameTest {
    /** Space the gauge leaves its right edge in the narrowest GUI, in GUI pixels. */
    private static final int MIN_GUI_WIDTH = 320;

    private static final int MIN_GUI_HEIGHT = 240;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        expectEachStateReadsAsItShould(context);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context,
                    "the player and level of the world",
                    client -> client.player != null && client.level != null);

            singleplayer.getServer().runOnServer(server -> PlacementStage.build(server, 1));
            ClientWait.until(context, "the player in layer 1", client -> TestLayers.clientIsIn(client, 1));
            require(
                    context.computeOnClient(client -> SuitGaugeHud.gauge(client).isEmpty() && noGaugeBox(client)),
                    "layer 1 asks nothing, so the gauge shows nothing and keeps nothing clear");

            singleplayer.getServer().runOnServer(PlacementStage::buildUnbuilt);
            ClientWait.until(
                    context,
                    "the suit exposed in the unbuilt depth",
                    client -> client.player != null
                            && client.player.getY() < CampaignBands.LAYER_2.minY()
                            && SuitGaugeHud.gauge(client).isPresent());
            expectTheGaugeKeepsItsBoxClear(context);

            setMode(singleplayer, GameType.CREATIVE);
            ClientWait.until(
                    context, "a creative player", client -> client.player != null && client.player.isCreative());
            require(
                    context.computeOnClient(client -> SuitGaugeHud.gauge(client).isEmpty() && noGaugeBox(client)),
                    "a creative player has no suit gauge");
            setMode(singleplayer, GameType.SURVIVAL);
            ClientWait.until(
                    context,
                    "a survival player with the gauge back",
                    client -> client.player != null
                            && !client.player.isCreative()
                            && SuitGaugeHud.gauge(client).isPresent());

            expectSeatedShowsNothing(context, singleplayer);
            expectAnUnreadableLogSaysSo(context, singleplayer);
        }
    }

    private static boolean noGaugeBox(Minecraft client) {
        return HudKeepClear.rects(
                        client,
                        client.getWindow().getGuiScaledWidth(),
                        client.getWindow().getGuiScaledHeight())
                .stream()
                .noneMatch(rect -> rect.what().equals("suit gauge"));
    }

    private static void setMode(TestSingleplayerContext singleplayer, GameType mode) {
        singleplayer
                .getServer()
                .runOnServer(
                        server -> server.getPlayerList().getPlayers().getFirst().setGameMode(mode));
    }

    /** The words, the colour and the blinking for each mode and reserve, and the box that holds the longest of them in the narrowest GUI. */
    private static void expectEachStateReadsAsItShould(ClientGameTestContext context) {
        int heatAndPressure = SuitState.bit(RatingAxis.HEAT) | SuitState.bit(RatingAxis.PRESSURE);
        int all = heatAndPressure | SuitState.bit(RatingAxis.FLOODING);
        Optional<Gauge> inert = SuitGaugeHud.gauge(new SuitState(100f, false, Mode.INERT, 0, 0f));
        require(inert.isEmpty(), "an inert suit shows nothing");

        Gauge fine = SuitGaugeHud.gauge(new SuitState(170f, true, Mode.EXPOSED, heatAndPressure, 5f))
                .orElseThrow();
        require(
                fine.label().getString().equals("SUIT 0:34")
                        && fine.axes().getString().equals("HEAT PRESSURE")
                        && fine.tone() == Tone.OK
                        && !fine.blinks()
                        && Math.abs(fine.fraction() - 170f / 180f) < 0.001f,
                "170 suit-seconds at 5 a second read 0:34 in the OK tone, read " + describe(fine));
        Gauge amber = SuitGaugeHud.gauge(new SuitState(100f, true, Mode.EXPOSED, heatAndPressure, 5.5f))
                .orElseThrow();
        require(
                amber.label().getString().equals("SUIT 0:19") && amber.tone() == Tone.AMBER && !amber.blinks(),
                "under half a minute reads amber, read " + describe(amber));
        Gauge red = SuitGaugeHud.gauge(new SuitState(40f, true, Mode.LEAKING, all, 5.5f))
                .orElseThrow();
        require(
                red.label().getString().equals("SUIT 0:08")
                        && red.axes().getString().equals("HEAT PRESSURE FLOODING")
                        && red.tone() == Tone.RED
                        && red.blinks(),
                "under ten seconds reads red and blinks, read " + describe(red));
        Gauge breached = SuitGaugeHud.gauge(new SuitState(0f, true, Mode.BREACHED, heatAndPressure, 5.5f))
                .orElseThrow();
        require(
                breached.label().getString().equals("SUIT BREACHED")
                        && breached.tone() == Tone.RED
                        && breached.blinks()
                        && breached.fraction() == 0f,
                "a breached suit says so, in red, blinking, read " + describe(breached));
        for (Mode mode : List.of(Mode.SHELTERED, Mode.POD_REFILL)) {
            Gauge refilling =
                    SuitGaugeHud.gauge(new SuitState(50f, true, mode, 0, 0f)).orElseThrow();
            require(
                    refilling.label().getString().equals("SUIT REFILLING")
                            && refilling.tone() == Tone.OK
                            && refilling.axes().getString().isEmpty(),
                    mode + " refills in the OK tone with no axes, read " + describe(refilling));
        }
        for (Mode mode : List.of(Mode.SHELTERED, Mode.POD_REFILL)) {
            // A sound hall in layer 1 is SHELTERED all the time: a full suit there has nothing to say.
            require(
                    SuitGaugeHud.gauge(new SuitState(180f, true, mode, 0, 0f)).isEmpty(),
                    mode + " with a full reserve shows nothing");
        }
        Gauge held = SuitGaugeHud.gauge(new SuitState(50f, true, Mode.POD_HOLD, 0, 0f))
                .orElseThrow();
        require(held.tone() == Tone.DIM, "a held suit is dim, read " + describe(held));

        // The widest the gauge gets: the longest line of words and every axis, in the narrowest GUI.
        context.runOnClient(client -> {
            for (Gauge gauge : List.of(fine, amber, red, breached)) {
                HudKeepClear.Rect box = SuitGaugeHud.box(client.font, gauge, MIN_GUI_WIDTH, MIN_GUI_HEIGHT);
                require(
                        box.x() >= 0
                                && box.right()
                                        <= MIN_GUI_WIDTH - SuitLook.current().rightMargin()
                                && box.y() >= 0
                                && box.bottom() <= MIN_GUI_HEIGHT,
                        "the gauge " + describe(gauge) + " at " + box + " leaves the narrowest GUI");
            }
        });
    }

    private static String describe(Gauge gauge) {
        return "'" + gauge.label().getString() + "' '" + gauge.axes().getString() + "' " + gauge.tone() + " "
                + (gauge.blinks() ? "blinking" : "steady") + " " + gauge.fraction();
    }

    /** The box is the one rectangle kept clear, and everything the gauge draws is inside it. */
    private static void expectTheGaugeKeepsItsBoxClear(ClientGameTestContext context) {
        context.runOnClient(client -> {
            int width = client.getWindow().getGuiScaledWidth();
            int height = client.getWindow().getGuiScaledHeight();
            List<HudKeepClear.Rect> mine = HudKeepClear.rects(client, width, height).stream()
                    .filter(rect -> rect.what().equals("suit gauge"))
                    .toList();
            require(mine.size() == 1, "the suit gauge keeps exactly one box clear, listed " + mine);
            Gauge gauge = SuitGaugeHud.gauge(client).orElseThrow();
            HudKeepClear.Rect expected = SuitGaugeHud.box(client.font, gauge, width, height);
            require(
                    mine.getFirst().equals(expected),
                    "the kept box " + mine.getFirst() + " is the drawn box " + expected);
            SuitLook look = SuitLook.current();
            require(
                    expected.right() == width - look.rightMargin() && expected.bottom() == height - look.bottomMargin(),
                    "the gauge sits at the bottom right, margins " + look.rightMargin() + " and " + look.bottomMargin()
                            + ", box " + expected);
            Box box = new Box(expected.x(), expected.y(), expected.width(), expected.height());
            List<Drawn> drawn = DrawnText.of(graphics -> SuitGaugeHud.extract(graphics, client.getDeltaTracker()));
            require(!drawn.isEmpty(), "the gauge should draw its line");
            for (Drawn text : drawn) {
                require(
                        text.box().x() >= box.x()
                                && text.box().y() >= box.y()
                                && text.box().right() <= box.right()
                                && text.box().bottom() <= box.bottom(),
                        "the gauge drew '" + text.shortText() + "' at " + text.box() + ", outside the box " + box
                                + " it keeps clear");
            }
        });
    }

    /** Seated in a pod the gauge is gone, whatever the suit does, and the pod's own readouts have the room. */
    private static void expectSeatedShowsNothing(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            PodEntity pod = TestEntities.create(PodRegistry.POD, player.level());
            pod.setPos(player.position());
            player.level().addFreshEntity(pod);
            if (!player.startRiding(pod, true, false)) {
                throw new AssertionError("the player could not mount the pod");
            }
        });
        ClientWait.until(
                context,
                "the player seated in a pod",
                client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
        require(
                context.computeOnClient(client -> SuitGaugeHud.gauge(client).isEmpty() && noGaugeBox(client)),
                "a seated person has no suit gauge");
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.stopRiding();
        });
        ClientWait.until(
                context,
                "the player on foot again",
                client -> client.player != null && !(client.player.getVehicle() instanceof PodEntity));
    }

    /** A suit log that this build cannot read shows one red line, so the person knows the timer is off. */
    private static void expectAnUnreadableLogSaysSo(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer
                .getServer()
                .runOnServer(server -> UnreadableChecks.makeUnreadable(
                        server.getPlayerList().getPlayers().getFirst(), SuitRegistry.SUIT));
        ClientWait.until(
                context,
                "the unreadable suit log on the client",
                client -> client.player != null
                        && client.player.getAttached(SuitRegistry.SUIT) instanceof Versioned.Unreadable<SuitState>);
        Gauge gauge =
                context.computeOnClient(client -> SuitGaugeHud.gauge(client).orElseThrow());
        require(
                gauge.label().getString().equals("SUIT LOG UNREADABLE") && gauge.tone() == Tone.RED,
                "an unreadable log says so in red, read " + describe(gauge));
        singleplayer
                .getServer()
                .runOnServer(server -> server.getPlayerList()
                        .getPlayers()
                        .getFirst()
                        .setAttached(SuitRegistry.SUIT, Versioned.of(SuitState.full())));
    }
}
