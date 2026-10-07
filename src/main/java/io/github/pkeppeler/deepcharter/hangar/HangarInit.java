package io.github.pkeppeler.deepcharter.hangar;

/**
 * Server entry point for the hangar feature, called from DeepCharter. Issues: #77.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class HangarInit {
	private HangarInit() {
	}

	public static void init() {
		HangarRegistry.register();
	}
}
