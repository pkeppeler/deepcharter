package io.github.pkeppeler.deepcharter.upgrade;

/** Registers the part items, the upgrade terminal's actions and its view payload. */
public final class UpgradeRegistry {
	private UpgradeRegistry() {
	}

	public static void register() {
		ComponentItems.register();
		UpgradeViewPayload.register();
		UpgradeTerminal.register();
	}
}
