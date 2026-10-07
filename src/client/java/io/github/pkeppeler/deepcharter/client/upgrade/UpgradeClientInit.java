package io.github.pkeppeler.deepcharter.client.upgrade;

/**
 * Client entry point for the upgrade feature, called from DeepCharterClient. Issues: #73.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class UpgradeClientInit {
	private UpgradeClientInit() {
	}

	public static void init() {
		UpgradeClientRegistry.register();
	}
}
