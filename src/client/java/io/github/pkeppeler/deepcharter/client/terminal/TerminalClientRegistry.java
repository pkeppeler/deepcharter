package io.github.pkeppeler.deepcharter.client.terminal;

import io.github.pkeppeler.deepcharter.terminal.RepairMenu;
import io.github.pkeppeler.deepcharter.terminal.TerminalViewPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;

/** Opens the terminal screen when the server sends a terminal view, and the repair screen when it opens a repair menu. */
public final class TerminalClientRegistry {
    private TerminalClientRegistry() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                TerminalViewPayload.TYPE, (payload, context) -> TerminalScreens.show(payload.view()));
        MenuScreens.register(RepairMenu.TYPE, RepairScreen::new);
    }
}
