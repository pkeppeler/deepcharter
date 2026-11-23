package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.COCKPIT_GUIS;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.GUI_SCALE;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.LONG_STEP;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.SHORT_STEP;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.critical;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.expectTheSlipIsDrawnWhereItIsPlacedAndCoversNoHud;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.requireClearOrNowhere;
import static io.github.pkeppeler.deepcharter.test.StepToastClearOfHudClientTest.sonarScope;
import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.pod.SonarHud;
import io.github.pkeppeler.deepcharter.client.theme.SonarLook;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** The popup's keep-clear check for the navigator's sonar scope: a sibling of {@link StepToastClearOfHudClientTest}, which has one player. */
public class StepToastClearOfSonarClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            two.server().runOnServer(server -> SonarHudClientTest.buildSite(server.overworld(), two));
            ClientWait.until(
                    context,
                    "the stone round the pod on the client",
                    client -> ClientLight.clientLevel(client)
                            .getBlockState(new BlockPos(
                                    SonarHudClientTest.X + SonarHudClientTest.SITE_RADIUS,
                                    SonarHudClientTest.FLOOR_Y,
                                    SonarHudClientTest.Z))
                            .is(Blocks.STONE));
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
                expectTheSlipIsClearOfTheScopeOnEveryCockpitGui(context);
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

    private static void expectTheSlipIsClearOfTheScopeOnEveryCockpitGui(ClientGameTestContext context) {
        context.runOnClient(client -> {
            for (int[] gui : COCKPIT_GUIS) {
                Map<String, Box> hud = critical(client, gui[0], gui[1]);
                require(hud.containsKey("sonar scope"), "the scope is up on a GUI " + gui[0] + " x " + gui[1]);
                requireClearOrNowhere(client, gui, SHORT_STEP, hud, true);
                requireClearOrNowhere(client, gui, LONG_STEP, hud, false);
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
