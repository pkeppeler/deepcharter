package io.github.pkeppeler.deepcharter.upgrade;

public final class UpgradeRegistry {
	// Registers the part items. The upgrade terminal joins them in #73.

	private UpgradeRegistry() {
	}

	public static void register() {
		ComponentItems.register();
	}
}
