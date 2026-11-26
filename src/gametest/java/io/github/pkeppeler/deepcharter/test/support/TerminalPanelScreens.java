package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.handbook.ClientHandbook;
import io.github.pkeppeler.deepcharter.client.hangar.HangarScreen;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreen;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene.Scene;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Opens the seven terminal screens on the real server, and waits until each has typed out its intro. {@link #dismissSceneSlips} clears
 * the first world's repair slips off them.
 */
public final class TerminalPanelScreens {
    /** The screens of the first world, by the id the stills use. */
    public static final List<String> SCENE_SCREENS = List.of("hangar", "processor", "upgrade", "repair", "pump");
    /** The screen of the second world. */
    public static final String CONTRACT = "contract";
    /** The screen of the third world. */
    public static final String GENERIC = "generic";

    /**
     * The directives {@code HandbookTriggers.creditRepairs} completes for the first world's repaired fuel pump, ore processor and upgrade
     * terminal, some ticks after the scene is set up; each one's "Directive complete" slip then draws over whatever screen is open (#1390).
     */
    private static final List<Identifier> SCENE_DIRECTIVES = List.of(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/back_online/repair_fuel_pump"),
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/back_online/repair_ore_processor"),
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/back_online/repair_upgrade_terminal"));

    private TerminalPanelScreens() {}

    /**
     * Waits until the first world's repair directives have reached the client, then clears their slips, so none lands on a terminal
     * still afterwards (#1390).
     */
    public static void dismissSceneSlips(ClientGameTestContext context) {
        ClientWait.until(
                context,
                "the repair directives synced to the client",
                client -> ClientHandbook.completed().containsAll(SCENE_DIRECTIVES),
                client -> "completed directives " + ClientHandbook.completed());
        context.runOnClient(client -> client.gui.toastManager().clear());
    }

    private static Scene sceneOf(@Nullable Scene scene, String id) {
        return Objects.requireNonNull(scene, () -> "the screen " + id + " needs the scene of the first world");
    }

    /** Opens the screen {@code id} at its terminal and waits until its intro has typed out and its content is there. Returns the screen. */
    public static Screen open(
            ClientGameTestContext context, String id, @Nullable Scene scene, @Nullable BlockPos elsewhere) {
        BlockPos at = switch (id) {
            case "hangar" -> sceneOf(scene, id).hangar();
            case "processor" -> sceneOf(scene, id).processor();
            case "upgrade" -> sceneOf(scene, id).upgrade();
            case "repair" -> sceneOf(scene, id).repair();
            case "pump" -> sceneOf(scene, id).pump();
            case CONTRACT, GENERIC ->
                Objects.requireNonNull(elsewhere, () -> "the screen " + id + " needs the position of its terminal");
            default -> throw new IllegalArgumentException("no terminal concept screen " + id);
        };
        Class<? extends Screen> type = switch (id) {
            case "hangar" -> HangarScreen.class;
            case "processor" -> OreProcessorScreen.class;
            case "upgrade" -> UpgradeScreen.class;
            case "repair" -> RepairStationScreen.class;
            case "pump" -> FuelPumpScreen.class;
            case GENERIC -> TerminalScreen.class;
            default -> ContractScreen.class;
        };
        context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(at)));
        ClientWait.screen(context, type);
        ClientWait.until(
                context,
                id + " typed out and filled in",
                client -> client.gui.screen() != null && typed(client.gui.screen()));
        context.waitTicks(
                4); // tick-wait: lets the last typed letter and the server's follow-up views settle into a still frame
        return context.computeOnClient(client -> client.gui.screen());
    }

    /** True when the screen's intro has typed out and, for the screens that wait for the server, what the server sends has come. */
    private static boolean typed(Screen screen) {
        return switch (screen) {
            case HangarScreen hangar -> done(hangar.typewriter());
            case OreProcessorScreen processor -> done(processor.typewriter());
            case UpgradeScreen upgrade ->
                done(upgrade.typewriter())
                        && upgrade.upgrade().flatMap(view -> view.pod()).isPresent();
            case RepairStationScreen repair -> done(repair.typewriter());
            case FuelPumpScreen pump -> done(pump.typewriter());
            case ContractScreen contract -> done(contract.typewriter()) && ContractShown.loaded(contract);
            case TerminalScreen generic -> done(generic.typewriter());
            default -> false;
        };
    }

    private static boolean done(Typewriter typewriter) {
        return typewriter.done();
    }

    public static void close(ClientGameTestContext context) {
        context.setScreen(() -> null);
    }
}
