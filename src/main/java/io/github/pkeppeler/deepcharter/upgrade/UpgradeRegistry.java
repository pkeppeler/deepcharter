package io.github.pkeppeler.deepcharter.upgrade;

/** Registers the part items, the upgrade terminal's action and its view feature. */
public final class UpgradeRegistry {
	private UpgradeRegistry() {
	}

	public static void register() {
		ComponentItems.register();
		UpgradeTerminal.register();
	}
}
