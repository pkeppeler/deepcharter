package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.QuestMapTuning;
import io.github.pkeppeler.deepcharter.client.handbook.StepToast;
import io.github.pkeppeler.deepcharter.client.layer.BlockReadoutHud;
import io.github.pkeppeler.deepcharter.client.layer.BreachHud;
import io.github.pkeppeler.deepcharter.client.market.AccountHud;
import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.pod.CockpitReading;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.client.pod.SeatedHotbar;
import io.github.pkeppeler.deepcharter.client.pod.SonarHud;
import io.github.pkeppeler.deepcharter.client.scanner.ScannerHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook.Layout;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook.Decal;
import io.github.pkeppeler.deepcharter.client.theme.ReadoutLook;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.client.theme.TransmissionLook;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionHud;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.client.ui.PixelDigits;
import io.github.pkeppeler.deepcharter.layer.Altimeter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.QuietTransmissions;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.transmission.TransmissionPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for #1030: a handbook step popup never covers the HUD. With the cockpit up in a pod, the popup is on screen and its
 * rectangle is clear of the ribbon, the hull and alert lamps, every gauge, the scanner and its bezel and the readout plate; where it
 * has no clear place it says so and none exists. The same holds on the GUI sizes the cockpit has (320 x 240, the smallest the game
 * allows, 427 x 240, the default window's, and bigger), on a GUI too small for the cockpit, where the plain pod readout, scanner panel
 * and altimeter are the HUD, with a transmission on screen, and on foot. The HUD's rectangles are worked out here from the theme and
 * the HUD's own public figures, not read from {@link HudKeepClear}, which the popup places itself by.
 */
public class StepToastClearOfHudClientTest implements FabricClientGameTest {
    private static final int FEET_Y = TestLayers.worldY(1, 150);
    private static final int ROOM_RADIUS = 8;
    private static final int ROOM_HEIGHT = 30;
    static final int GUI_SCALE = 2;
    /** The GUI sizes with a cockpit: the game's smallest (320 x 240), the default window's (427 x 240), and two larger. */
    static final int[][] COCKPIT_GUIS = {{320, 240}, {427, 240}, {640, 360}, {854, 480}};
    /** A GUI too small for the cockpit, where the plain HUD is drawn. */
    private static final int[] PLAIN_GUI = {300, 200};

    static final String SHORT_STEP = "Reach the Deep Claim.";
    static final String LONG_STEP =
            "Carry the whole of a very long instruction through the hatch, past the second sump pump and the old tally"
                    + " board, down three ladders to the Company's first fuel depot, and sign for every barrel.";
    private static final String CHARTER = "Toast Charter";

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40); // tick-wait: the world settles before the pod is built
            context.runOnClient(client -> client.options.guiScale().set(GUI_SCALE));
            EvidencePins.lightFlickerOff = true;
            EvidencePins.handsHidden = true;
            try {
                seatThePilot(context, singleplayer);
                expectTheSlipIsDrawnWhereItIsPlacedAndCoversNoHud(context, LONG_STEP, "in the cockpit", true);
                expectTheSlipIsClearOnEveryCockpitGui(context);
                expectTheSlipIsClearOnAGuiTooSmallForTheCockpit(context);
                expectTheSlipIsClearOfATransmission(context);
                singleplayer
                        .getServer()
                        .runOnServer(server ->
                                server.getPlayerList().getPlayers().getFirst().stopRiding());
                ClientWait.until(
                        context,
                        "the pilot out of the pod",
                        client -> client.player != null && client.player.getVehicle() == null);
                expectTheSlipIsDrawnWhereItIsPlacedAndCoversNoHud(context, LONG_STEP, "on foot", false);
                expectTheSlipIsClearOnFoot(context);
            } finally {
                EvidencePins.reset();
                context.runOnClient(client -> {
                    TransmissionOverlay.reset();
                    client.gui.toastManager().clear();
                });
            }
        }
    }

    private static void seatThePilot(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            RoomCarver.carve(
                    one,
                    -ROOM_RADIUS,
                    ROOM_RADIUS,
                    FEET_Y - 1,
                    FEET_Y + ROOM_HEIGHT,
                    -ROOM_RADIUS,
                    ROOM_RADIUS,
                    Blocks.AIR);
        });
        ClientWait.lightSettled(context, singleplayer.getServer(), 1, 0, 0, ROOM_RADIUS);
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(one, 0.5, FEET_Y, 0.5, Set.of(), 0f, 0f, true);
        });
        ClientWait.arrived(context, singleplayer.getServer());
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            PodEntity pod = TestEntities.create(PodRegistry.POD, one);
            pod.setPos(0.5, FEET_Y, 0.5);
            pod.setNoGravity(true);
            one.addFreshEntity(pod);
            ScannerPods.fit(server, player, pod, 1);
            if (!player.startRiding(pod)) {
                throw new AssertionError("the player could not mount the pod");
            }
        });
        ClientWait.until(
                context,
                "the pilot seated in the pod in layer 1 with a scanner map",
                client -> client.player != null
                        && client.player.getVehicle() instanceof PodEntity
                        && TestLayers.clientIsIn(client, 1)
                        && ScannerHud.hasMap());
        context.runOnClient(client -> {
            TransmissionOverlay.reset();
            client.gui.toastManager().clear();
        });
    }

    /**
     * The popup the toast manager really draws, at the window's own GUI. It is drawn where {@link StepToast#placement} says (the text
     * the manager draws is inside that box, read through a recorder, not taken from the toast's own figures); that box is where
     * {@link StepToast#place} puts it; none of the HUD, as worked out here from the theme, is under it; and none of the text the real
     * HUD layers draw is. {@code step} is its text and {@code cockpit} says whether the cockpit is up.
     */
    static void expectTheSlipIsDrawnWhereItIsPlacedAndCoversNoHud(
            ClientGameTestContext context, String step, String when, boolean cockpit) {
        StepToast[] toast = {null};
        context.runOnClient(client -> {
            toast[0] = StepToast.of(Component.literal(step));
            client.gui.toastManager().addToast(toast[0]);
        });
        ClientWait.until(
                context,
                "the step popup " + when + " drawn inside the box it was placed in",
                client -> {
                    Optional<HudKeepClear.Rect> placed = toast[0].placement();
                    if (placed.isEmpty()) {
                        return false;
                    }
                    Box slip = box(placed.get());
                    List<Drawn> drawn = probeText(client, slip);
                    return !drawn.isEmpty()
                            && drawn.stream()
                                    .allMatch(text -> text.box().x() >= slip.x()
                                            && text.box().y() >= slip.y()
                                            && text.box().right() <= slip.right()
                                            && text.box().bottom() <= slip.bottom());
                },
                client -> "placement " + toast[0].placement() + ", the manager drew "
                        + DrawnText.of(graphics -> client.gui.toastManager().extractRenderState(graphics)));
        context.runOnClient(client -> {
            int width = client.getWindow().getGuiScaledWidth();
            int height = client.getWindow().getGuiScaledHeight();
            require(
                    CockpitHud.seated(client).isPresent() == cockpit,
                    "the window's GUI " + width + " x " + height + (cockpit ? " has" : " has not") + " the cockpit up");
            Box slip = box(toast[0].placement().orElseThrow());
            Map<String, Box> hud = critical(client, width, height);
            requireClear("the step popup " + when + " at " + width + " x " + height, slip, hud);
            for (HudKeepClear.Rect kept : HudKeepClear.rects(client, width, height)) {
                require(
                        !ClientChecks.overlaps(slip, box(kept)),
                        "the step popup " + when + " at " + slip + " covers the registered keep-clear '" + kept.what()
                                + "' at " + box(kept));
            }
            if (cockpit) {
                require(hud.containsKey("hull and alert lamps"), "the cockpit HUD has lamps to keep clear of");
                if (width <= COCKPIT_GUIS[1][0]) {
                    requireTheOldSlotWasWrong(width, hud);
                }
            }
            require(
                    StepToast.place(
                                    width,
                                    height,
                                    toast[0].width(),
                                    toast[0].height(),
                                    HudKeepClear.rects(client, width, height))
                            .map(StepToast.Placement::slip)
                            .map(StepToastClearOfHudClientTest::box)
                            .equals(Optional.of(slip)),
                    "the popup is where StepToast.place puts it, not somewhere the toast manager moved it");
            List<Drawn> hudText = hudText(client);
            require(!hudText.isEmpty(), "the mod's HUD parts draw some text " + when);
            for (Drawn text : hudText) {
                require(
                        !ClientChecks.overlaps(slip, text.box()),
                        "the step popup " + when + " at " + slip + " covers the HUD text '" + text.shortText() + "' at "
                                + text.box());
            }
        });
        context.runOnClient(client -> client.gui.toastManager().clear());
    }

    /**
     * The text the toast manager draws for the probe alone. Other toasts (the campaign's step popups) draw too, and are not the probe's:
     * the probe's text is its step's words, and its heading, which is whatever else lands on its box.
     */
    private static List<Drawn> probeText(Minecraft client, Box slip) {
        return DrawnText.of(graphics -> client.gui.toastManager().extractRenderState(graphics)).stream()
                .filter(text -> LONG_STEP.contains(text.text()) || ClientChecks.overlaps(slip, text.box()))
                .toList();
    }

    private static void expectTheSlipIsClearOnEveryCockpitGui(ClientGameTestContext context) {
        context.runOnClient(client -> {
            for (int[] gui : COCKPIT_GUIS) {
                require(
                        CockpitLook.current().fits(gui[0], gui[1]),
                        "the cockpit fits a GUI " + gui[0] + " x " + gui[1]);
                Map<String, Box> hud = critical(client, gui[0], gui[1]);
                require(hud.containsKey("hull and alert lamps"), "the lamps are on a GUI " + gui[0] + " x " + gui[1]);
                if (gui[0] <= COCKPIT_GUIS[1][0]) {
                    requireTheOldSlotWasWrong(gui[0], hud);
                }
                for (String step : List.of(SHORT_STEP, LONG_STEP)) {
                    requireClearOrNowhere(client, gui, step, hud, true);
                }
            }
        });
    }

    private static void expectTheSlipIsClearOnAGuiTooSmallForTheCockpit(ClientGameTestContext context) {
        context.runOnClient(client -> {
            require(
                    !CockpitLook.current().fits(PLAIN_GUI[0], PLAIN_GUI[1]),
                    "a GUI " + PLAIN_GUI[0] + " x " + PLAIN_GUI[1] + " is too small for the cockpit");
            Map<String, Box> hud = critical(client, PLAIN_GUI[0], PLAIN_GUI[1]);
            require(hud.containsKey("pod readout"), "the plain pod readout is up on a GUI too small for the cockpit");
            require(
                    hud.containsKey("scanner panel"),
                    "the plain scanner panel is up on a GUI too small for the cockpit");
            requireClearOrNowhere(client, PLAIN_GUI, SHORT_STEP, hud, false);
            requireClearOrNowhere(client, PLAIN_GUI, LONG_STEP, hud, false);
        });
    }

    private static void expectTheSlipIsClearOfATransmission(ClientGameTestContext context) {
        try (var _ = QuietTransmissions.shown()) {
            context.runOnClient(client -> TransmissionOverlay.enqueue(new TransmissionPayload(
                    Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "t01"), CHARTER, "Director")));
            ClientWait.until(context, "a transmission on screen", client -> TransmissionOverlay.active());
            context.runOnClient(client -> {
                for (int[] gui : COCKPIT_GUIS) {
                    Map<String, Box> hud = critical(client, gui[0], gui[1]);
                    require(
                            hud.containsKey("transmission"),
                            "the transmission is among the HUD on " + gui[0] + " x " + gui[1]);
                    requireClearOrNowhere(client, gui, SHORT_STEP, hud, false);
                }
                TransmissionOverlay.reset();
            });
        }
    }

    private static void expectTheSlipIsClearOnFoot(ClientGameTestContext context) {
        context.runOnClient(client -> {
            for (int[] gui : new int[][] {COCKPIT_GUIS[0], COCKPIT_GUIS[1], PLAIN_GUI}) {
                Map<String, Box> hud = critical(client, gui[0], gui[1]);
                require(hud.containsKey("altimeter"), "the altimeter is up on foot on " + gui[0] + " x " + gui[1]);
                require(
                        !hud.containsKey("cockpit ribbon") && !hud.containsKey("pod readout"),
                        "on foot there is no cockpit and no pod readout");
                requireClearOrNowhere(client, gui, SHORT_STEP, hud, true);
                requireClearOrNowhere(client, gui, LONG_STEP, hud, gui[0] >= COCKPIT_GUIS[0][0]);
            }
        });
    }

    /**
     * The popup of {@code step} on {@code gui}: where {@link StepToast#place} puts it against the HUD's own rectangles, and that it is
     * clear of {@code hud}, worked out here. Where it finds no place, that every place in the left and in the right column is under some
     * part of the HUD. {@code placed} says it must find one.
     */
    static void requireClearOrNowhere(Minecraft client, int[] gui, String step, Map<String, Box> hud, boolean placed) {
        StepToast toast = StepToast.of(Component.literal(step));
        String when = "'" + step.substring(0, Math.min(24, step.length())) + "' on " + gui[0] + " x " + gui[1];
        Optional<Box> slip = StepToast.place(
                        gui[0], gui[1], toast.width(), toast.height(), HudKeepClear.rects(client, gui[0], gui[1]))
                .map(StepToast.Placement::slip)
                .map(StepToastClearOfHudClientTest::box);
        if (slip.isPresent()) {
            requireClear("the step popup " + when, slip.get(), hud);
            return;
        }
        require(!placed, "the step popup " + when + " finds a place");
        int margin = QuestMapTuning.current().toastMargin();
        for (int x : new int[] {margin, gui[0] - margin - toast.width()}) {
            for (int y = margin; y + toast.height() <= gui[1] - margin; y++) {
                Box candidate = new Box(x, y, toast.width(), toast.height());
                require(
                        hud.values().stream().anyMatch(part -> ClientChecks.overlaps(candidate, part)),
                        "the step popup " + when + " found no place, but " + candidate + " is clear of the HUD");
            }
        }
    }

    private static void requireClear(String what, Box slip, Map<String, Box> hud) {
        hud.forEach((part, box) -> require(
                !ClientChecks.overlaps(slip, box), what + " at " + slip + " covers the " + part + " at " + box));
    }

    /**
     * The text the mod's HUD parts draw now, each part's own draw method run into a recorder: what a player reads, wherever the part puts it.
     * The block readout and the transmission draw only while they are up.
     */
    private static List<Drawn> hudText(Minecraft client) {
        DeltaTracker tracker = client.getDeltaTracker();
        List<Drawn> drawn = new ArrayList<>();
        for (BiConsumer<GuiGraphicsExtractor, DeltaTracker> part :
                List.<BiConsumer<GuiGraphicsExtractor, DeltaTracker>>of(
                        CockpitHud::extract,
                        PodStatusHud::extract,
                        ScannerHud::extract,
                        BreachHud::extractAltimeter,
                        AccountHud::extract,
                        BlockReadoutHud::extract,
                        SonarHud::extract,
                        TransmissionHud::extract)) {
            drawn.addAll(DrawnText.of(graphics -> part.accept(graphics, tracker)));
        }
        return drawn;
    }

    /**
     * The vanilla toast slot, top right, is where the popup was, and on a GUI as narrow as the ribbon (the default window's or under) it
     * covers the lamps: the checks here would see that, so they can fail.
     */
    private static void requireTheOldSlotWasWrong(int guiWidth, Map<String, Box> hud) {
        StepToast toast = StepToast.of(Component.literal(SHORT_STEP));
        Box oldSlot = new Box(guiWidth - toast.width(), 0, toast.width(), toast.height());
        Box lamps = Objects.requireNonNull(hud.get("hull and alert lamps"), "the lamps are up");
        require(
                ClientChecks.overlaps(oldSlot, lamps),
                "the vanilla slot " + oldSlot + " covers the lamps " + lamps + " on " + guiWidth);
    }

    /**
     * Every part of the HUD that is up for the local player on a GUI {@code width} x {@code height}, from the theme and the HUD's
     * public figures: in the cockpit the ribbon, each gauge, the lamps, the scanner window and bezel, the readout plate and vanilla's hotbar; without
     * it the plain pod readout and scanner panel; the altimeter, the account line and a transmission wherever they are drawn. The
     * navigator's sonar scope is the one part read from {@link HudKeepClear}: its layout is private to the HUD, so
     * {@link StepToastClearOfSonarClientTest} checks that rectangle against what the scope draws.
     */
    static Map<String, Box> critical(Minecraft client, int width, int height) {
        Map<String, Box> hud = new LinkedHashMap<>();
        var font = client.font;
        var player = ClientSession.player(client);
        ClientLevel level = Objects.requireNonNull(client.level, "the client is in a world");
        Optional<Layout> seated = CockpitHud.seated(client, width, height);
        if (seated.isPresent()) {
            Layout ribbon = seated.get();
            hud.put("cockpit ribbon", box(ribbon.band(), width, height));
            // Vanilla's hotbar, which a seated pilot keeps (#1457): 182 x 22 at the bottom centre, its selection frame
            // a pixel round it.
            hud.put("hotbar", new Box(width / 2 - 92, height - 23, 184, 23));
            hud.put("fuel gauge", box(ribbon.fuel().area(), width, height));
            hud.put("hull gauge", box(ribbon.hull().area(), width, height));
            hud.put("cargo gauge", box(ribbon.cargo().area(), width, height));
            hud.put("depth gauge", box(ribbon.depth().area(), width, height));
            hud.put("heat gauge", box(ribbon.heat().area(), width, height));
            hud.put("hull and alert lamps", box(ribbon.lamps(), width, height));
            if (ScannerHud.hasMap()) {
                hud.put("scanner window", box(ribbon.scanner(), width, height));
                hud.put("scanner bezel", box(ribbon.cap(), width, height));
            }
            if (CockpitHud.showsReadout(client, width, height)) {
                PodEntity pod = (PodEntity) Objects.requireNonNull(player.getVehicle(), "the pilot is in a pod");
                var look = CockpitLook.current();
                var rows = CockpitHud.plateRows(
                        font,
                        ribbon,
                        look,
                        CockpitHud.rows(
                                pod,
                                CockpitReading.of(pod, CockpitHud.heat()),
                                look,
                                PodStatusHud.readout(player, pod)),
                        SeatedHotbar.slots(client, width, height),
                        width,
                        height);
                var plate = CockpitHud.plateBox(font, ribbon, look, rows, width, height);
                hud.put("readout plate", new Box(plate.left(), plate.top(), plate.width(), plate.height()));
            }
        } else if (player.getVehicle() instanceof PodEntity pod) {
            HudLook look = HudLook.current();
            var lines = PodStatusHud.readout(player, pod);
            int widest = lines.stream()
                    .mapToInt(line -> font.width(line.text()))
                    .max()
                    .orElse(0);
            int pitch = font.lineHeight + look.podStatusLineGap();
            hud.put(
                    "pod readout",
                    new Box(
                            look.podStatusMargin(),
                            look.podStatusMargin(),
                            widest,
                            lines.size() * pitch - look.podStatusLineGap()));
            ScanArea area = ScanSlice.scan(level, pod).orElseThrow().area();
            int cell = ScannerHud.cellSize(width, height, area);
            int left = ScannerHud.cellLeft(width, height, area, -area.halfWidth());
            int bottom = ScannerHud.cellTop(width, height, area, -area.down()) + cell;
            int margin = ScannerLook.current().margin();
            hud.put(
                    "scanner panel",
                    new Box(
                            left - 1,
                            margin,
                            ScannerHud.cellLeft(width, height, area, area.halfWidth()) + cell + 1 - left + 1,
                            bottom + 1 - margin));
        }
        if (SonarHud.frame(client).isPresent()) {
            hud.put("sonar scope", sonarScope(client, width, height));
        }
        if (seated.isEmpty()) {
            CockpitLook.Digits digits = CockpitLook.current().digits();
            int readingWidth = PixelDigits.width(
                    Altimeter.reading(level, player.getBlockY()).getString(), digits);
            hud.put(
                    "altimeter",
                    new Box(
                            (width - readingWidth) / 2,
                            HudLook.current().altimeterMargin(),
                            readingWidth,
                            digits.height()));
        }
        Optional<Component> account = AccountHud.text();
        // On foot and seated alike (#1642).
        if (account.isPresent()) {
            hud.put(
                    "account line",
                    new Box(
                            HudLook.current().accountMargin(),
                            (int) (height * HudLook.current().accountY()),
                            font.width(account.get()),
                            font.lineHeight));
        }
        BlockReadoutHud.shown().ifPresent(readout -> {
            ReadoutLook look = ReadoutLook.current();
            int widest = font.width(readout.name());
            int lines = readout.hint().isPresent() ? 2 : 1;
            if (readout.hint().isPresent()) {
                widest = Math.max(widest, font.width(readout.hint().get()));
            }
            double scale = look.scale();
            int boxHeight = (int) Math.ceil((lines * font.lineHeight + (lines - 1) * look.lineGap()) * scale);
            hud.put(
                    "block readout",
                    new Box(
                            (width - (int) Math.ceil(widest * scale)) / 2,
                            look.blockTop(height, font.lineHeight),
                            (int) Math.ceil(widest * scale),
                            boxHeight));
        });
        if (TransmissionOverlay.active()) {
            TransmissionLook look = TransmissionLook.current();
            CrtTuning crt = CrtTuning.current();
            int panelWidth = Math.min(look.maxWidth(), width - 2 * look.screenMargin());
            int lines = font.getSplitter()
                    .splitLines(
                            FormattedText.of(TransmissionOverlay.bodyFull()),
                            panelWidth - 2 * crt.padding(),
                            Style.EMPTY)
                    .size();
            int step = font.lineHeight + crt.lineSpacing();
            int panelHeight = 2 * crt.padding() + step + look.headerGap() + lines * step;
            int top = Math.max(look.screenMargin(), Math.round(height * (float) look.centerY() - panelHeight / 2f));
            hud.put("transmission", new Box((width - panelWidth) / 2, top, panelWidth, panelHeight));
        }
        return hud;
    }

    /** The rectangle the sonar HUD registers to be kept clear, as it stands on a GUI {@code width} x {@code height}. */
    static Box sonarScope(Minecraft client, int width, int height) {
        return HudKeepClear.rects(client, width, height).stream()
                .filter(rect -> rect.what().equals("sonar scope"))
                .findFirst()
                .map(StepToastClearOfHudClientTest::box)
                .orElseThrow(() -> new AssertionError(
                        "the sonar HUD registers no keep-clear rectangle on " + width + " x " + height));
    }

    private static Box box(Decal decal, int width, int height) {
        return new Box(decal.left(width), decal.top(height), decal.width(), decal.height());
    }

    static Box box(HudKeepClear.Rect rect) {
        return new Box(rect.x(), rect.y(), rect.width(), rect.height());
    }
}
