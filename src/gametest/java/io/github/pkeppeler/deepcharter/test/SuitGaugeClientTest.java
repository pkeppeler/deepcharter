package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.suit.SuitGaugeHud;
import io.github.pkeppeler.deepcharter.client.suit.SuitGaugeHud.Gauge;
import io.github.pkeppeler.deepcharter.client.suit.SuitGaugeHud.Tone;
import io.github.pkeppeler.deepcharter.client.theme.SuitLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitState;
import io.github.pkeppeler.deepcharter.suit.SuitState.Mode;
import io.github.pkeppeler.deepcharter.suit.SuitTracks;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.PlacementStage;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.VanillaHudRows;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Client GameTest for the suit gauge (#993): how it reads for each state of the suit (words, colour, blinking), that it shows nothing where
 * the suit is inert, in a pod's seat and for a creative player, that a suit log this build cannot read says so in red, that the one
 * rectangle {@link HudKeepClear} gives for it is the box its text is drawn in, and that on the smallest GUI and the default window's it
 * fits and is clear of vanilla's rows ({@link VanillaHudRows}). Live, the gauge draws only on foot, with a draining suit or an
 * unreadable log, and in both it is checked clear of every other box {@link HudKeepClear} lists; seated in a pod, with either, it
 * draws and keeps nothing, so it cannot cover the pod's readout at the top left or the cockpit art. The live part runs in
 * the unbuilt depth, where the real suit drains, so it reads what the client holds and not fixed numbers.
 */
public class SuitGaugeClientTest implements FabricClientGameTest {
    /** The smallest GUI the game allows and the default window's at GUI scale 2. */
    private static final int[][] GUIS = {{320, 240}, {427, 240}};

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
        // Short by a level on each axis, four bits an axis: heat in the low bits.
        int shortFive = 0x055;
        int shortTwo = 0x222;
        SuitTracks stock = SuitTracks.STOCK;
        Optional<Gauge> inert = SuitGaugeHud.gauge(new SuitState(100f, false, Mode.INERT, 0f, 0), stock);
        require(inert.isEmpty(), "an inert suit shows nothing");

        Gauge fine = SuitGaugeHud.gauge(new SuitState(170f, true, Mode.EXPOSED, 5f, shortFive), stock)
                .orElseThrow();
        require(
                fine.label().getString().equals("SUIT 0:34")
                        && fine.axes().getString().equals("HEAT -5 PRESSURE -5")
                        && fine.tone() == Tone.OK
                        && !fine.blinks()
                        && Math.abs(fine.fraction() - 170f / 180f) < 0.001f,
                "170 suit-seconds at 5 a second read 0:34 in the OK tone, read " + describe(fine));
        Gauge amber = SuitGaugeHud.gauge(new SuitState(100f, true, Mode.EXPOSED, 5.5f, shortFive), stock)
                .orElseThrow();
        require(
                amber.label().getString().equals("SUIT 0:19") && amber.tone() == Tone.AMBER && !amber.blinks(),
                "under half a minute reads amber, read " + describe(amber));
        Gauge red = SuitGaugeHud.gauge(new SuitState(40f, true, Mode.LEAKING, 5.5f, shortTwo), stock)
                .orElseThrow();
        require(
                red.label().getString().equals("SUIT 0:08")
                        && red.axes().getString().equals("HEAT -2 PRESSURE -2 FLOODING -2")
                        && red.tone() == Tone.RED
                        && red.blinks(),
                "under ten seconds reads red and blinks, read " + describe(red));
        Gauge breached = SuitGaugeHud.gauge(new SuitState(0f, true, Mode.BREACHED, 5.5f, shortFive), stock)
                .orElseThrow();
        require(
                breached.label().getString().equals("SUIT BREACHED")
                        && breached.tone() == Tone.RED
                        && breached.blinks()
                        && breached.fraction() == 0f,
                "a breached suit says so, in red, blinking, read " + describe(breached));
        for (Mode mode : List.of(Mode.SHELTERED, Mode.POD_REFILL)) {
            Gauge refilling = SuitGaugeHud.gauge(new SuitState(50f, true, mode, 0f, 0), stock)
                    .orElseThrow();
            require(
                    refilling.label().getString().equals("SUIT REFILLING")
                            && refilling.tone() == Tone.OK
                            && refilling.axes().getString().isEmpty(),
                    mode + " refills in the OK tone with no axes, read " + describe(refilling));
        }
        for (Mode mode : List.of(Mode.SHELTERED, Mode.POD_REFILL)) {
            // A sound hall in layer 1 is SHELTERED all the time: a full suit there has nothing to say.
            require(
                    SuitGaugeHud.gauge(new SuitState(180f, true, mode, 0f, 0), stock)
                            .isEmpty(),
                    mode + " with a full reserve shows nothing");
        }
        Gauge held = SuitGaugeHud.gauge(new SuitState(50f, true, Mode.POD_HOLD, 0f, 0), stock)
                .orElseThrow();
        require(held.tone() == Tone.DIM, "a held suit is dim, read " + describe(held));

        // The bar is the reserve over the suit's own capacity, and a full suit that refills hides at its own.
        SuitTracks airThree = new SuitTracks(0, 0, 3);
        Gauge half = SuitGaugeHud.gauge(new SuitState(180f, true, Mode.EXPOSED, 5f, shortFive), airThree)
                .orElseThrow();
        require(
                Math.abs(half.fraction() - 0.5f) < 0.001f
                        && half.label().getString().equals("SUIT 0:36"),
                "180 of an air tier 3 suit's 360 is half a bar, read " + describe(half));
        Gauge stockFull = SuitGaugeHud.gauge(new SuitState(180f, true, Mode.EXPOSED, 5f, shortFive), stock)
                .orElseThrow();
        require(stockFull.fraction() == 1f, "180 of a stock suit's 180 is a whole bar, read " + describe(stockFull));
        require(
                SuitGaugeHud.gauge(new SuitState(180f, true, Mode.SHELTERED, 0f, 0), airThree)
                        .isPresent(),
                "a refilling air tier 3 suit at 180 is half full and still shows");
        require(
                SuitGaugeHud.gauge(new SuitState(360f, true, Mode.SHELTERED, 0f, 0), airThree)
                        .isEmpty(),
                "a refilling air tier 3 suit at 360 is full and shows nothing");

        // The widest and tallest the gauge gets, on the smallest GUI and the default window's: inside it and clear of
        // vanilla's rows.
        Gauge unreadable = new Gauge(
                Component.translatable("hud.deepcharter.suit.unreadable"), Component.empty(), 0f, Tone.RED, false);
        context.runOnClient(client -> {
            for (int[] gui : GUIS) {
                for (Gauge gauge : List.of(fine, amber, red, breached, unreadable)) {
                    HudKeepClear.Rect box = SuitGaugeHud.box(client.font, gauge);
                    require(
                            box.x() >= 0 && box.right() <= gui[0] && box.y() >= 0 && box.bottom() <= gui[1],
                            "the gauge " + describe(gauge) + " at " + box + " leaves the GUI " + gui[0] + " x "
                                    + gui[1]);
                    for (HudKeepClear.Rect row : VanillaHudRows.rects(gui[0], gui[1])) {
                        require(
                                !box.overlaps(row),
                                "the gauge " + describe(gauge) + " at " + box + " runs into vanilla's " + row.what()
                                        + " " + row + " on " + gui[0] + " x " + gui[1]);
                    }
                }
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
            HudKeepClear.Rect expected = SuitGaugeHud.box(client.font, gauge);
            require(
                    mine.getFirst().equals(expected),
                    "the kept box " + mine.getFirst() + " is the drawn box " + expected);
            SuitLook look = SuitLook.current();
            require(
                    expected.x() == look.leftMargin() && expected.y() == look.topMargin(),
                    "the gauge sits at the top left, margins " + look.leftMargin() + " and " + look.topMargin()
                            + ", box " + expected);
            for (int[] gui : GUIS) {
                for (HudKeepClear.Rect other : HudKeepClear.rects(client, gui[0], gui[1])) {
                    require(
                            other.what().equals("suit gauge") || !expected.overlaps(other),
                            "the gauge " + expected + " runs into the " + other.what() + " " + other + " on " + gui[0]
                                    + " x " + gui[1]);
                }
            }
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
        mountPod(context, singleplayer);
        expectNothingDrawnOrKept(context, "a seated person has no suit gauge");
        dismount(context, singleplayer);
    }

    private static void mountPod(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
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
    }

    private static void dismount(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.stopRiding();
        });
        ClientWait.until(
                context,
                "the player on foot again",
                client -> client.player != null && !(client.player.getVehicle() instanceof PodEntity));
    }

    /**
     * The gauge shows nothing, keeps nothing clear and draws nothing, so it cannot cover the pod's readout at the top left or the
     * cockpit art.
     */
    private static void expectNothingDrawnOrKept(ClientGameTestContext context, String why) {
        context.runOnClient(client -> {
            Optional<Gauge> gauge = SuitGaugeHud.gauge(client);
            require(gauge.isEmpty() && noGaugeBox(client), why + ", read " + gauge.map(SuitGaugeClientTest::describe));
            List<Drawn> drawn = DrawnText.of(graphics -> SuitGaugeHud.extract(graphics, client.getDeltaTracker()));
            require(
                    drawn.isEmpty(),
                    why + ", drew " + drawn.stream().map(Drawn::shortText).toList());
        });
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
        expectTheGaugeKeepsItsBoxClear(context);
        mountPod(context, singleplayer);
        expectNothingDrawnOrKept(context, "a seated person with an unreadable suit log has no suit gauge");
        dismount(context, singleplayer);
        singleplayer
                .getServer()
                .runOnServer(server -> server.getPlayerList()
                        .getPlayers()
                        .getFirst()
                        .setAttached(SuitRegistry.SUIT, Versioned.of(SuitState.full())));
        // Unreadable tracks cannot rate the suit, so the gauge says that its log is unreadable too.
        singleplayer
                .getServer()
                .runOnServer(server -> UnreadableChecks.makeUnreadable(
                        server.getPlayerList().getPlayers().getFirst(), SuitRegistry.TRACKS));
        ClientWait.until(
                context,
                "the unreadable suit tracks on the client",
                client -> client.player != null
                        && client.player.getAttached(SuitRegistry.TRACKS) instanceof Versioned.Unreadable<SuitTracks>);
        Gauge noTracks =
                context.computeOnClient(client -> SuitGaugeHud.gauge(client).orElseThrow());
        require(
                noTracks.label().getString().equals("SUIT LOG UNREADABLE") && noTracks.tone() == Tone.RED,
                "unreadable tracks say the log is unreadable, read " + describe(noTracks));
        singleplayer
                .getServer()
                .runOnServer(server -> server.getPlayerList()
                        .getPlayers()
                        .getFirst()
                        .setAttached(SuitRegistry.TRACKS, Versioned.of(SuitTracks.STOCK)));
    }
}
