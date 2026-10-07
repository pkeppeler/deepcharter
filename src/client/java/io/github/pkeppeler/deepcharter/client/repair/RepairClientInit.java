package io.github.pkeppeler.deepcharter.client.repair;

/**
 * Client entry point for the repair feature, called from DeepCharterClient. Issues: #70.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class RepairClientInit {
	private RepairClientInit() {
	}

	public static void init() {
		RepairClientRegistry.register();
	}
}
