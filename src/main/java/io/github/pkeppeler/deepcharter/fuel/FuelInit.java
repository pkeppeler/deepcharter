package io.github.pkeppeler.deepcharter.fuel;

/**
 * Server entry point for the fuel feature, called from DeepCharter. Issues: #69.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class FuelInit {
	private FuelInit() {
	}

	public static void init() {
		FuelRegistry.register();
	}
}
