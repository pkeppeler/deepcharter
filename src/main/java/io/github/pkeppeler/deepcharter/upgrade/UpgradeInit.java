package io.github.pkeppeler.deepcharter.upgrade;

/**
 * Server entry point for the upgrade feature, called from DeepCharter. Issues: #73.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class UpgradeInit {
	private UpgradeInit() {
	}

	public static void init() {
		UpgradeRegistry.register();
	}
}
