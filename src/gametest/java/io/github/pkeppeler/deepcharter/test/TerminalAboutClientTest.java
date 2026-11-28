package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.hangar.HangarScreen;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.ui.AboutTip;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.terminal.TerminalWares;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScreenScroll;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Client GameTest for #1645: every row a terminal sells says what it does before the player buys it. On the repair
 * station, the fuel pump and the hangar console, at the GUI size of an 854 by 480 window (about 427 by 240) and at
 * 320 by 240, the smallest GUI, each row carries the line {@code TerminalWares} holds for its ware, the tip shows it
 * for the row the pointer is on and for the row with the focus, and it wraps to a few rows inside the content area,
 * clear of the row it belongs to. Between them the rows show every line of the terminal's wares (the soot rows,
 * which need a pod parked at the station, are covered by {@code TerminalWaresTest} and the evidence scenario).
 */
public class TerminalAboutClientTest implements FabricClientGameTest {
    private static final int[][] SIZES = {{427, 240}, {320, 240}};

    /** The wares a pod's soot adds to the repair station's list: no pod is parked in this test. */
    private static final Set<String> NEEDS_A_POD =
            Set.of(RepairStation.ware(RepairStation.SERVICE_ENGINE), RepairStation.ware(RepairStation.SCRAPE_FLUE));

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext _ = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the client in the world", client -> client.player != null && client.level != null);
            for (int[] size : SIZES) {
                String at = " at " + size[0] + " by " + size[1];
                TerminalView station = view(TerminalTypes.REPAIR_STATION);
                context.setScreen(() -> new RepairStationScreen(station));
                RepairStationScreen repair =
                        context.computeOnClient(client -> ClientSession.screen(client, RepairStationScreen.class));
                context.runOnClient(client -> repair.resize(size[0], size[1]));
                ClientChecks.requireNone(
                        "the repair station" + at, context.computeOnClient(client -> repairProblem(repair)));

                TerminalView pump = view(TerminalTypes.FUEL_PUMP);
                context.setScreen(() -> new FuelPumpScreen(pump));
                FuelPumpScreen fuel =
                        context.computeOnClient(client -> ClientSession.screen(client, FuelPumpScreen.class));
                context.runOnClient(client -> fuel.resize(size[0], size[1]));
                ClientChecks.requireNone(
                        "the fuel pump" + at,
                        context.computeOnClient(client -> problem(fuel, fuel.tipBounds(), TerminalTypes.FUEL_PUMP)));

                TerminalView console = view(HangarTerminal.TYPE);
                context.setScreen(() -> new HangarScreen(console));
                HangarScreen hangar =
                        context.computeOnClient(client -> ClientSession.screen(client, HangarScreen.class));
                context.runOnClient(client -> hangar.resize(size[0], size[1]));
                ClientChecks.requireNone(
                        "the hangar console" + at,
                        context.computeOnClient(client -> problem(hangar, hangar.tipBounds(), HangarTerminal.TYPE)));
            }
            context.setScreen(() -> null);
        }
    }

    private static TerminalView view(TerminalType type) {
        return new TerminalView(BlockPos.ZERO, type.id(), Optional.empty(), List.of());
    }

    /** The list scrolls, so each row is looked at once it is shown. */
    private static Optional<String> repairProblem(RepairStationScreen screen) {
        AboutTip.Bounds bounds = screen.tipBounds();
        Set<String> seen = new HashSet<>();
        for (int first = 0; first < screen.rowCount(); first++) {
            ScreenScroll.scrollTo(screen, first);
            Optional<String> problem = rowsProblem(screen, bounds, seen);
            if (problem.isPresent()) {
                return Optional.of("scrolled to row " + first + ": " + problem.get());
            }
        }
        return missing(TerminalTypes.REPAIR_STATION, seen, NEEDS_A_POD);
    }

    private static Optional<String> problem(Screen screen, AboutTip.Bounds bounds, TerminalType type) {
        Set<String> seen = new HashSet<>();
        Optional<String> problem = rowsProblem(screen, bounds, seen);
        return problem.isPresent() ? problem : missing(type, seen, Set.of());
    }

    /** Every row but CLOSE has a line, the tip shows it by pointer and by focus, and it fits the screen. */
    private static Optional<String> rowsProblem(Screen screen, AboutTip.Bounds bounds, Set<String> seen) {
        Minecraft client = Minecraft.getInstance();
        List<CrtButton> rows = screen.children().stream()
                .filter(CrtButton.class::isInstance)
                .map(CrtButton.class::cast)
                .toList();
        for (CrtButton button : rows) {
            String label = button.getMessage().getString();
            if (label.equals(
                    Component.translatable("screen.deepcharter.terminal.close").getString())) {
                continue;
            }
            if (button.about().isEmpty()) {
                return Optional.of("'" + label + "' says nothing about what it does");
            }
            Component about = button.about().get();
            seen.add(about.getString());
            client.setLastInputType(InputType.KEYBOARD_TAB);
            screen.setFocused(button);
            if (!AboutTip.pointed(screen, -1, -1).equals(Optional.of(button))) {
                return Optional.of("the tip does not show the row the keys moved to: '" + label + "'");
            }
            client.setLastInputType(InputType.MOUSE);
            if (AboutTip.pointed(screen, -1, -1).isPresent()) {
                return Optional.of("the tip stays open on '" + label + "' after a click, with the pointer away");
            }
            screen.setFocused(null);
            if (!AboutTip.pointed(screen, button.getX() + 1, button.getY() + 1).equals(Optional.of(button))) {
                return Optional.of("the tip does not show the pointed-at '" + label + "'");
            }
            AboutTip.Box box = AboutTip.boxFor(client.font, bounds, screen, button);
            if (box.lines().size() > AboutTip.MAX_LINES) {
                return Optional.of(
                        "the line for '" + label + "' wraps to " + box.lines().size() + " rows, over the "
                                + AboutTip.MAX_LINES + " the tip has: " + about.getString());
            }
            if (box.x() < bounds.left()
                    || box.y() < bounds.top()
                    || box.x() + box.width() > bounds.right()
                    || box.y() + box.height() > bounds.bottom()) {
                return Optional.of("the tip for '" + label + "' leaves the content area " + bounds + ": " + box);
            }
            if (box.covers(button)) {
                return Optional.of("the tip for '" + label + "' covers the row it belongs to: " + box);
            }
            for (CrtButton other : rows) {
                if (other != button && other.about().isPresent() && other.visible && box.covers(other)) {
                    return Optional.of("the tip for '" + label + "' covers the row '"
                            + other.getMessage().getString() + "': " + box);
                }
            }
        }
        if (AboutTip.pointed(screen, -1, -1).isPresent()) {
            return Optional.of("the tip shows a line with nothing pointed at");
        }
        return Optional.empty();
    }

    /** The rows between them showed every line the terminal's wares hold, bar those that need a pod. */
    private static Optional<String> missing(TerminalType type, Set<String> seen, Set<String> skipped) {
        Set<String> absent = TerminalWares.of(type).stream()
                .filter(ware -> !skipped.contains(ware.id()))
                .map(ware -> Component.translatable(ware.aboutKey()).getString())
                .filter(line -> !seen.contains(line))
                .collect(Collectors.toSet());
        return absent.isEmpty() ? Optional.empty() : Optional.of("no row showed " + absent);
    }
}
