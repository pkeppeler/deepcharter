package io.github.pkeppeler.deepcharter.terminal;

import io.github.pkeppeler.deepcharter.menu.MenuRefusalPayload;

/**
 * Registers the terminal parts and types (with their blocks, items and block entity), the repair menu with the slotted
 * menus' refusal payload, and the terminal's three payloads.
 */
public final class TerminalRegistry {
    private TerminalRegistry() {}

    public static void register() {
        TerminalParts.register();
        TerminalTypes.register();
        RepairMenu.register();
        MenuRefusalPayload.register();
        TerminalViewPayload.register();
        TerminalOpenPayload.register();
        TerminalActionPayload.register();
    }
}
