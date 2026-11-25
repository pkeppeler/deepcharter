package io.github.pkeppeler.deepcharter.terminal;

/**
 * Registers the terminal parts and types (with their blocks, items and block entity), the repair menu and the three
 * payloads.
 */
public final class TerminalRegistry {
    private TerminalRegistry() {}

    public static void register() {
        TerminalParts.register();
        TerminalTypes.register();
        RepairMenu.register();
        TerminalViewPayload.register();
        TerminalOpenPayload.register();
        TerminalActionPayload.register();
    }
}
