package io.github.pkeppeler.deepcharter.ore;

/**
 * Server entry point for the ore feature, called from DeepCharter. Issues: #56, #63.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class OreInit {
	private OreInit() {
	}

	public static void init() {
		OreRegistry.register();
		OreEncumbrance.init();
	}
}
