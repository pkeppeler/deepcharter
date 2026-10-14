package io.github.pkeppeler.deepcharter.upgrade;

/** Registers the part items, the crafted units, the upgrade terminal's action and its view feature. */
public final class UpgradeRegistry {
    private UpgradeRegistry() {}

    public static void register() {
        ComponentItems.register();
        PartUnits.register();
        UpgradeTerminal.register();
    }
}
