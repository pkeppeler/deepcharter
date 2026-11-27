package io.github.pkeppeler.deepcharter.client.terminal;

import io.github.pkeppeler.deepcharter.menu.MenuRefusalPayload;
import io.github.pkeppeler.deepcharter.menu.SlottedMenu;
import io.github.pkeppeler.deepcharter.terminal.RepairMenu;
import io.github.pkeppeler.deepcharter.terminal.TerminalViewPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * Opens the terminal screen when the server sends a terminal view, and the repair screen when it opens a repair menu;
 * shows a slotted menu's refusal the server sends.
 */
public final class TerminalClientRegistry {
    private TerminalClientRegistry() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                TerminalViewPayload.TYPE, (payload, context) -> TerminalScreens.show(payload.view()));
        MenuScreens.register(RepairMenu.TYPE, RepairScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(MenuRefusalPayload.TYPE, (payload, context) -> {
            if (context.player().containerMenu instanceof SlottedMenu menu
                    && menu.containerId == payload.containerId()) {
                menu.refusedByServer(payload.reason());
            }
        });
    }
}
