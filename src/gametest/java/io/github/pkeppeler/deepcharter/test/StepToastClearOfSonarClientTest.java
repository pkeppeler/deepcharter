package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.COCKPIT_GUIS;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.GUI_SCALE;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.LONG_STEP;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.SHORT_STEP;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.box;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.critical;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.expectTheSlipIsDrawnWhereItIsPlacedAndCoversNoHud;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.requireClearOrNowhere;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.sonarScope;
import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.handbook.QuestMapTuning;
import io.github.pkeppeler.deepcharter.client.handbook.StepToast;
import io.github.pkeppeler.deepcharter.client.pod.SonarHud;
import io.github.pkeppeler.deepcharter.client.theme.SonarLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear.Rect;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;

/** The popup's keep-clear check for the navigator's sonar scope: a sibling of {@link StepToastClearOfHudClientTest}, which has one player. */
public class StepToastClearOfSonarClientTest implements FabricClientGameTest {
    /** Longer than the popup's {@code toastMillis} (5 s at 20 ticks a second), shorter than its {@code toastWaitMillis}. */
    private static final int WAIT_TICKS = 140;
    /**
     * Whether the test's own keep-clear rectangle, over the whole GUI, is up. The source stays registered, as {@link HudKeepClear} has no
     * unregister; that is safe because the flag is reset in a {@code finally} and a source that is off adds nothing.
     */
    private static final AtomicBoolean FILL_THE_GUI = new AtomicBoolean();

    static {
        HudKeepClear.register((client, guiWidth, guiHeight, into) -> {
            if (FILL_THE_GUI.get()) {
                into.add(new Rect("test wall", 0, 0, guiWidth, guiHeight));
            }
        });
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            two.server().runOnServer(server -> SonarHudClientTest.buildSite(server.overworld(), two));
            ClientWait.chunksAround(
                    context, SonarHudClientTest.X, SonarHudClientTest.Z, SonarHudClientTest.SITE_RADIUS);
            ClientWait.lightSettled(
                    context,
                    two.server(),
                    TestLayers.SURFACE,
                    SonarHudClientTest.X,
                    SonarHudClientTest.Z,
                    SonarHudClientTest.SITE_RADIUS);
            int podId = two.server().computeOnServer(server -> SonarHudClientTest.board(server.overworld(), two));
            context.runOnClient(client -> client.options.guiScale().set(GUI_SCALE));
            EvidencePins.lightFlickerOff = true;
            EvidencePins.handsHidden = true;
            try {
                ClientWait.until(
                        context,
                        "the client riding the pod as its navigator",
                        client -> client.player != null
                                && client.player.getVehicle() instanceof PodEntity pod
                                && pod.getId() == podId
                                && pod.getPassengers().size() == 2
                                && pod.getPassengers().get(1) == client.player);
                ClientWait.until(
                        context,
                        "the sonar scope for the navigator",
                        client -> SonarHud.frame(client).isPresent());
                context.runOnClient(client -> client.gui.toastManager().clear());
                expectTheScopeRectangleCoversWhatTheScopeDraws(context);
                expectTheSlipIsDrawnWhereItIsPlacedAndCoversNoHud(context, SHORT_STEP, "as the navigator", true);
                expectTheSlipIsDrawnWhereItIsPlacedAndCoversNoHud(context, LONG_STEP, "as the navigator, long", true);
                expectTheLongSlipIsInTheRightColumn(context);
                expectTheLongSlipIsBelowAVanillaToast(context);
                expectTheSlipIsClearOfTheScopeOnEveryCockpitGui(context);
                expectASlipWithNoRoomWaitsQueuedUntilThereIsRoom(context);
            } finally {
                EvidencePins.reset();
                context.runOnClient(client -> client.gui.toastManager().clear());
            }
        }
    }

    /**
     * The rectangle the popup keeps clear of is the scope: it holds the frame and the charge bar, worked out here from the theme, and
     * every string the sonar HUD draws, and the critical rectangles the popup is checked against include it.
     */
    private static void expectTheScopeRectangleCoversWhatTheScopeDraws(ClientGameTestContext context) {
        context.runOnClient(client -> {
            int width = client.getWindow().getGuiScaledWidth();
            int height = client.getWindow().getGuiScaledHeight();
            Box rect = sonarScope(client, width, height);
            SonarLook look = SonarLook.current();
            int top = (height - look.scopePixels()) / 2;
            Box frame = new Box(look.margin() - 1, top - 1, look.scopePixels() + 2, look.scopePixels() + 2);
            requireInside("the scope's frame", frame, rect);
            require(
                    rect.bottom() >= top + look.scopePixels() + SonarHud.LINE_GAP + SonarHud.BAR_HEIGHT,
                    "the scope's keep-clear rectangle " + rect + " ends above its charge bar");
            List<Drawn> text = DrawnText.of(graphics -> SonarHud.extract(graphics, client.getDeltaTracker()));
            require(!text.isEmpty(), "the sonar HUD draws some text for the navigator");
            for (Drawn drawn : text) {
                requireInside("the sonar text '" + drawn.shortText() + "'", drawn.box(), rect);
            }
            require(
                    critical(client, width, height).containsKey("sonar scope"),
                    "the popup is checked against the sonar scope while the navigator has one");
        });
    }

    /** On the default window's GUI the long step fits neither above nor below the scope, so it goes to the right column (#1075). */
    private static void expectTheLongSlipIsInTheRightColumn(ClientGameTestContext context) {
        context.runOnClient(client -> {
            int width = client.getWindow().getGuiScaledWidth();
            int height = client.getWindow().getGuiScaledHeight();
            require(
                    width == COCKPIT_GUIS[1][0] && height == COCKPIT_GUIS[1][1],
                    "the window's GUI is " + COCKPIT_GUIS[1][0] + " x " + COCKPIT_GUIS[1][1] + ", not " + width + " x "
                            + height);
            StepToast toast = StepToast.of(Component.literal(LONG_STEP));
            Box scope = sonarScope(client, width, height);
            int margin = QuestMapTuning.current().toastMargin();
            int gap = QuestMapTuning.current().toastGap();
            require(
                    scope.y() - gap - margin < toast.height()
                            && height - margin - (scope.bottom() + gap) < toast.height(),
                    "the long step " + toast.width() + " x " + toast.height() + " fits above or below the scope "
                            + scope + ", so the left column would take it");
            Optional<StepToast.Placement> slip = StepToast.place(
                    width, height, toast.width(), toast.height(), HudKeepClear.rects(client, width, height));
            require(slip.isPresent(), "the long step finds a place beside the navigator's scope");
            require(
                    slip.get().right() && slip.get().slip().x() == width - margin - toast.width(),
                    "the long step is in the right column, not " + slip.get());
        });
    }

    /**
     * A vanilla toast takes the right column's top slot, so the long step goes under it, never over it (#1075): the toast manager
     * draws a toast at the top right, which no HUD part lists.
     */
    private static void expectTheLongSlipIsBelowAVanillaToast(ClientGameTestContext context) {
        StepToast[] toast = {null};
        context.runOnClient(client -> client.gui.toastManager().addToast(new SlotToast()));
        ClientWait.until(
                context,
                "the vanilla toast among the rectangles the popup keeps clear of",
                client ->
                        HudKeepClear.rects(
                                        client,
                                        client.getWindow().getGuiScaledWidth(),
                                        client.getWindow().getGuiScaledHeight())
                                .stream()
                                .anyMatch(rect -> rect.what().equals("vanilla toast")));
        context.runOnClient(client -> {
            toast[0] = StepToast.of(Component.literal(LONG_STEP));
            client.gui.toastManager().addToast(toast[0]);
        });
        ClientWait.until(
                context,
                "the long step placed beside the vanilla toast",
                client -> toast[0].placement().isPresent(),
                client -> "placement " + toast[0].placement());
        context.runOnClient(client -> {
            int width = client.getWindow().getGuiScaledWidth();
            int height = client.getWindow().getGuiScaledHeight();
            Box slip = box(toast[0].placement().orElseThrow());
            Rect vanilla = HudKeepClear.rects(client, width, height).stream()
                    .filter(rect -> rect.what().equals("vanilla toast"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the vanilla toast is gone"));
            require(!ClientChecks.overlaps(slip, box(vanilla)), "the popup at " + slip + " covers the " + vanilla);
            require(slip.y() >= vanilla.bottom(), "the popup at " + slip + " is not under the " + vanilla);
            requireClearOfEvery(slip, HudKeepClear.rects(client, width, height));
            client.gui.toastManager().clear();
        });
    }

    /** A plain toast as vanilla's are: the default size and place, top right, and shown until cleared. */
    private static final class SlotToast implements Toast {
        @Override
        public Visibility getWantedVisibility() {
            return Visibility.SHOW;
        }

        @Override
        public void update(ToastManager manager, long fullyVisibleForMs) {}

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {}
    }

    /**
     * With no room anywhere (a rectangle over the whole GUI, as a part of the HUD that has grown to fill it), the popup is drawn
     * nowhere and its time does not run; when the rectangle goes, it is drawn, and it shows its whole time, longer than it waited.
     */
    private static void expectASlipWithNoRoomWaitsQueuedUntilThereIsRoom(ClientGameTestContext context) {
        StepToast[] toast = {null};
        FILL_THE_GUI.set(true);
        try {
            context.runOnClient(client -> {
                toast[0] = StepToast.of(Component.literal(LONG_STEP));
                client.gui.toastManager().addToast(toast[0]);
            });
            // tick-wait: an absence has nothing to await, so the popup is given longer than its whole display time to
            // show up
            context.waitTicks(WAIT_TICKS);
            context.runOnClient(client -> {
                require(toast[0].placement().isEmpty(), "with no room the popup has no place: " + toast[0].placement());
                List<Drawn> drawn =
                        DrawnText.of(graphics -> client.gui.toastManager().extractRenderState(graphics));
                require(drawn.isEmpty(), "with no room the popup draws nothing, but the manager drew " + drawn);
            });
        } finally {
            FILL_THE_GUI.set(false);
        }
        ClientWait.until(
                context,
                "the queued popup drawn once there is room",
                client -> toast[0].placement().isPresent()
                        && !DrawnText.of(graphics -> client.gui.toastManager().extractRenderState(graphics))
                                .isEmpty(),
                client -> "placement " + toast[0].placement());
        context.runOnClient(client -> {
            int width = client.getWindow().getGuiScaledWidth();
            int height = client.getWindow().getGuiScaledHeight();
            requireClearOfEvery(box(toast[0].placement().orElseThrow()), HudKeepClear.rects(client, width, height));
            client.gui.toastManager().clear();
        });
    }

    private static void requireClearOfEvery(Box slip, List<Rect> rects) {
        for (Rect rect : rects) {
            require(!ClientChecks.overlaps(slip, box(rect)), "the popup at " + slip + " covers the " + rect);
        }
    }

    private static void expectTheSlipIsClearOfTheScopeOnEveryCockpitGui(ClientGameTestContext context) {
        context.runOnClient(client -> {
            for (int[] gui : COCKPIT_GUIS) {
                Map<String, Box> hud = critical(client, gui[0], gui[1]);
                require(hud.containsKey("sonar scope"), "the scope is up on a GUI " + gui[0] + " x " + gui[1]);
                requireClearOrNowhere(client, gui, SHORT_STEP, hud, true);
                requireClearOrNowhere(client, gui, LONG_STEP, hud, true);
            }
        });
    }

    private static void requireInside(String what, Box part, Box whole) {
        require(
                part.x() >= whole.x()
                        && part.y() >= whole.y()
                        && part.right() <= whole.right()
                        && part.bottom() <= whole.bottom(),
                what + " at " + part + " is outside the sonar scope's keep-clear rectangle " + whole);
    }
}
