package io.github.pkeppeler.deepcharter.client.ore;

/**
 * Client entry point for the ore feature, called from DeepCharterClient. Issues: #56, #63.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class OreClientInit {
	private OreClientInit() {
	}

	public static void init() {
		OreClientRegistry.register();
	}
}
