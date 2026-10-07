package io.github.pkeppeler.deepcharter.client.hangar;

/**
 * Client entry point for the hangar feature, called from DeepCharterClient. Issues: #77.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class HangarClientInit {
	private HangarClientInit() {
	}

	public static void init() {
		HangarClientRegistry.register();
	}
}
