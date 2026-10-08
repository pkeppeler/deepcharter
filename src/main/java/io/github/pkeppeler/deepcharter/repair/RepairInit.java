package io.github.pkeppeler.deepcharter.repair;

/**
 * Server entry point for the repair feature, called from DeepCharter. Issues: #70.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class RepairInit {
	private RepairInit() {
	}

	public static void init() {
		RepairRegistry.register();
	}
}
