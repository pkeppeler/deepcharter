package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.hangar.HangarScreen;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarView;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeature;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.test.FuelPumpClientTest;
import io.github.pkeppeler.deepcharter.test.RepairStationClientTest;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScreenScroll;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;

/**
 * Evidence scenario "terminal-about" for #1645: the repair station, the fuel pump and the hangar console each say
 * what a row does while the player browses it, before buying. A row at a time is focused (the keyboard's way to the
 * same tip the pointer opens), and the tip under the row shows its line. Stills of each screen with a line showing.
 */
public class TerminalAboutScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int HOLD_FRAMES = 6;
    private static final int TYPING_FRAMES = 60;
    /** The repair station's rows that are looked at: the hull, an item, a suit track. */
    private static final List<String> REPAIR_ROWS = List.of("REPAIR ALL", "BUY DYNAMITE $120", "SUIT HEAT T1 $450");

    @Override
    protected String name() {
        return "terminal-about";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            RepairStationClientTest.Scene scene =
                    singleplayer.getServer().computeOnServer(RepairStationClientTest::setUp);
            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.station())));
            ClientWait.screen(context, RepairStationScreen.class);
            RepairStationScreen screen =
                    context.computeOnClient(client -> ClientSession.screen(client, RepairStationScreen.class));
            ClientWait.until(
                    context, "the charter view", client -> ClientCharter.view().isPresent());
            typeOut(context, screen.typewriter()::done);
            for (String row : REPAIR_ROWS) {
                focusRow(context, screen, row);
                screenshot(context, "repair-" + row.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "-"));
            }
            context.setScreen(() -> null);
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            FuelPumpClientTest.Scene scene = singleplayer.getServer().computeOnServer(FuelPumpClientTest::setUp);
            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.pump())));
            ClientWait.screen(context, FuelPumpScreen.class);
            FuelPumpScreen screen =
                    context.computeOnClient(client -> ClientSession.screen(client, FuelPumpScreen.class));
            typeOut(context, screen.typewriter()::done);
            focusRow(context, screen, "BUY 5 L");
            screenshot(context, "pump-buy");
            focusRow(context, screen, "FILL UP");
            screenshot(context, "pump-fill");
            context.setScreen(() -> null);
        }
        try (TestSingleplayerContext _ = context.worldBuilder().create()) {
            awaitWorld(context);
            TerminalView view = new TerminalView(
                    BlockPos.ZERO,
                    HangarTerminal.TYPE.id(),
                    Optional.<TerminalFeature>of(new HangarView(0)),
                    List.of());
            context.setScreen(() -> new HangarScreen(view));
            HangarScreen screen = context.computeOnClient(client -> ClientSession.screen(client, HangarScreen.class));
            typeOut(context, screen.typewriter()::done);
            focusRow(context, screen, "BUY REFURBISHED MOLE: $");
            screenshot(context, "hangar-buy");
            focusRow(context, screen, "RESTORE NEAREST WRECK");
            screenshot(context, "hangar-restore");
            context.setScreen(() -> null);
        }
    }

    private void typeOut(ClientGameTestContext context, BooleanSupplier done) {
        for (int i = 0; i < TYPING_FRAMES && !context.computeOnClient(client -> done.getAsBoolean()); i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    /**
     * Scrolls the list to the row whose label starts with {@code label} if it is not shown, focuses it and holds a
     * few frames.
     */
    private void focusRow(ClientGameTestContext context, Screen screen, String label) {
        context.runOnClient(client -> {
            // The keyboard's way to the tip: a click's focus shows none.
            client.setLastInputType(InputType.KEYBOARD_TAB);
            if (screen instanceof RepairStationScreen repair) {
                for (int first = 0;
                        first < repair.rowCount() && shown(screen, label).isEmpty();
                        first++) {
                    ScreenScroll.scrollTo(screen, first);
                }
            }
            screen.setFocused(shown(screen, label).orElseThrow(() -> new AssertionError("no row '" + label + "'")));
        });
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    private static Optional<CrtButton> shown(Screen screen, String label) {
        return screen.children().stream()
                .filter(CrtButton.class::isInstance)
                .map(CrtButton.class::cast)
                .filter(button -> button.getMessage().getString().startsWith(label))
                .findFirst();
    }
}
