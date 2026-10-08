package io.github.pkeppeler.deepcharter.client.fuel;

/**
 * Client entry point for the fuel feature, called from DeepCharterClient. Issues: #69.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class FuelClientInit {
	private FuelClientInit() {
	}

	public static void init() {
		FuelClientRegistry.register();
	}
}
