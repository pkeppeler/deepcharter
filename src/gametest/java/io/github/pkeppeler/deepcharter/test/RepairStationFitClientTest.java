package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RepairRows;
import io.github.pkeppeler.deepcharter.test.support.ScreenScroll;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Client GameTest for #268: the repair station fits the screen at the GUI size of an 854 by 480 window (about 427 by 240) and
 * at 320 by 240, the smallest GUI. The account is the longest the screen can show, and the hull line is both the one with a
 * pod and the longer one without. At every scroll position each button and text line, the typed-out intro included, is inside
 * the screen and clear of the others, and between them the positions show every row of the list. The pod is limp with
 * a four-digit service, so both soot rows (#1280) show at their widest and must clear their buttons' edges.
 */
public class RepairStationFitClientTest implements FabricClientGameTest {
    private static final String LAST_ROW = "BUY MATTER TRANSMITTER $1650";
    /** Soot whose full service costs four digits: the widest the paid row gets. */
    private static final float SOOT = 113f;

    private static final List<String> SOOT_ROWS = List.of("SERVICE ALL 113 L ($1130)", "SCRAPE FLUE (FREE, SLOW)");

    /**
     * Every other label a soot row can take, at its widest: a part service and a countdown of four digits each, and
     * the row of an account short of a litre. The list cannot be put in those states with the account above, so their
     * labels are measured against the service row's button.
     */
    private static final List<Component> OTHER_SOOT_LABELS = List.of(
            Component.translatable("screen.deepcharter.repair.service_part", 103, 113, 1030),
            Component.translatable("screen.deepcharter.repair.service_unpaid", 1000),
            Component.translatable("screen.deepcharter.repair.scraping", 1000));

    private static final long LONGEST_DEPOSIT = 9_999_999_999_999L;
    private static final int FAR = 1000;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            RepairStationClientTest.Scene scene =
                    singleplayer.getServer().computeOnServer(RepairStationClientTest::setUp);
            singleplayer.getServer().runOnServer(server -> {
                require(
                        Charters.deposit(server, scene.charter().id(), LONGEST_DEPOSIT)
                                .isEmpty(),
                        "funding should succeed");
                // Limp, so both soot rows show, and enough soot for a four-digit service.
                scene.pod().setAttached(EngineSoot.STATE, Versioned.of(new EngineSoot.State(SOOT, 0f, 0f)));
            });
            ClientWait.until(
                    context,
                    "the soot on the client",
                    client -> client.level != null
                            && client.level.getEntity(scene.pod().getId()) instanceof PodEntity pod
                            && EngineSoot.soot(pod) == SOOT);
            ClientWait.until(
                    context,
                    "the account above the longest deposit",
                    client -> ClientCharter.view()
                            .map(charter -> charter.balance() > LONGEST_DEPOSIT)
                            .orElse(false),
                    client -> "charter " + ClientCharter.view());
            BlockPos farAway = scene.station().offset(FAR, 0, FAR);
            // Both hull lines: the pod parked at the station, and no pod.
            for (BlockPos at : List.of(scene.station(), farAway)) {
                TerminalView view =
                        new TerminalView(at, TerminalTypes.REPAIR_STATION.id(), Optional.empty(), List.of());
                // A size and the fewest rows it must show: a smaller list is a layout that wastes the screen.
                for (int[] size : new int[][] {{427, 240, 4}, {320, 240, 3}}) {
                    context.setScreen(() -> new RepairStationScreen(view));
                    RepairStationScreen screen =
                            context.computeOnClient(client -> ClientSession.screen(client, RepairStationScreen.class));
                    context.runOnClient(client -> screen.resize(size[0], size[1]));
                    ClientChecks.requireNone(
                            "at " + size[0] + " by " + size[1] + (at == farAway ? " without a pod" : " with a pod"),
                            context.computeOnClient(client -> problem(screen, size[2], at != farAway)));
                }
            }
            context.setScreen(() -> null);
        }
    }

    private static Optional<String> problem(RepairStationScreen screen, int fewestRows, boolean withPod) {
        if (screen.visibleRows() < fewestRows) {
            return Optional.of("the list shows " + screen.visibleRows() + " rows, fewer than " + fewestRows);
        }
        if (!screen.getNarrationMessage().getString().contains("OF " + screen.rowCount())) {
            return Optional.of("the narration does not say which rows are shown: "
                    + screen.getNarrationMessage().getString());
        }
        Set<String> seen = new HashSet<>();
        ScreenScroll.scrollTo(screen, 0);
        for (int first = 0; first < screen.rowCount(); first++) {
            ScreenScroll.scrollTo(screen, first);
            Optional<String> problem = stepProblem(screen, seen);
            if (problem.isPresent()) {
                return Optional.of("scrolled to row " + RepairRows.firstRow(screen) + ": " + problem.get());
            }
        }
        if (!seen.contains(LAST_ROW)) {
            return Optional.of("the list never shows '" + LAST_ROW + "'");
        }
        if (withPod && !seen.containsAll(SOOT_ROWS)) {
            return Optional.of("the list never shows the soot rows " + SOOT_ROWS + ", it shows " + seen);
        }
        // The rows, and CLOSE.
        if (seen.size() != screen.rowCount() + 1) {
            return Optional.of("the list shows " + (seen.size() - 1) + " rows of " + screen.rowCount());
        }
        return keyProblem(screen);
    }

    /** Down from the last shown row scrolls one row and focuses the row that came into view; Up from the first row goes back. */
    private static Optional<String> keyProblem(RepairStationScreen screen) {
        ScreenScroll.scrollTo(screen, 0);
        List<Button> buttons = ClientChecks.buttons(screen);
        Button last = buttons.get(screen.visibleRows() - 1);
        screen.setFocused(last);
        screen.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
        if (RepairRows.firstRow(screen) != 1
                || !(screen.getFocused() instanceof Button now)
                || now.getY() != last.getY()) {
            return Optional.of("Down from the last row did not scroll one row and focus the row that came into view");
        }
        Button first = ClientChecks.buttons(screen).getFirst();
        screen.setFocused(first);
        screen.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, InputConstants.MOD_SHIFT));
        if (RepairRows.firstRow(screen) != 0
                || !(screen.getFocused() instanceof Button back)
                || back.getY() != first.getY()) {
            return Optional.of("Shift+Tab from the first row did not scroll back one row and focus it");
        }
        return Optional.empty();
    }

    /**
     * A soot row's label clears both edges of its button by the panel's margin, in the game's font and in the terminal
     * font after the panel's pip (#1280: the four-digit service touched the edge).
     */
    private static Optional<String> sootLabelCrowded(Button button) {
        List<String> labels = new ArrayList<>(List.of(button.getMessage().getString()));
        OTHER_SOOT_LABELS.forEach(other -> labels.add(other.getString()));
        Font font = Minecraft.getInstance().font;
        for (String label : labels) {
            int plain = font.width(label) + 2 * PanelLook.LABEL_MARGIN;
            int terminal = CrtText.width(font, label);
            int panelEnd = PanelLook.current().labelStart(button.getWidth(), terminal, true)
                    + terminal
                    + PanelLook.LABEL_MARGIN;
            if (plain > button.getWidth() || panelEnd > button.getWidth()) {
                return Optional.of("'" + label + "' needs " + Math.max(plain, panelEnd)
                        + " px with its margins in a button " + button.getWidth() + " wide");
            }
        }
        return Optional.empty();
    }

    /** Every button and text line lies inside the screen, a button holds its whole label, and nothing overlaps. */
    private static Optional<String> stepProblem(RepairStationScreen screen, Set<String> seen) {
        List<Button> buttons = ClientChecks.buttons(screen);
        List<RepairStationScreen.TextLine> lines = new ArrayList<>(screen.introLines());
        lines.addAll(screen.textLines());
        for (Button button : buttons) {
            String label = button.getMessage().getString();
            seen.add(label);
            Optional<String> problem = ClientChecks.buttonLeavesScreen(screen, button)
                    .or(() -> ClientChecks.labelClipped(button))
                    .or(() -> SOOT_ROWS.contains(label) ? sootLabelCrowded(button) : Optional.empty())
                    .or(() -> ClientChecks.widgetOverlaps(button, buttons));
            if (problem.isPresent()) {
                return problem;
            }
        }
        for (RepairStationScreen.TextLine line : lines) {
            ClientChecks.Box box = ClientChecks.Box.ofText(line.x(), line.y(), line.text());
            Optional<String> problem = ClientChecks.textLeavesScreen(
                            screen, "the line", line.x(), line.y(), line.text())
                    .or(() -> !line.dim() && box.bottom() > screen.listTop()
                            ? Optional.of("the line '" + line.text() + "' ends at " + box.bottom()
                                    + ", past where the list starts at " + screen.listTop())
                            : Optional.empty())
                    .or(() -> ClientChecks.textUnderButton("the line", line.x(), line.y(), line.text(), buttons));
            if (problem.isPresent()) {
                return problem;
            }
            for (RepairStationScreen.TextLine other : lines) {
                if (other != line
                        && ClientChecks.overlaps(box, ClientChecks.Box.ofText(other.x(), other.y(), other.text()))) {
                    return Optional.of("the line '" + line.text() + "' overlaps '" + other.text() + "'");
                }
            }
        }
        return Optional.empty();
    }
}
