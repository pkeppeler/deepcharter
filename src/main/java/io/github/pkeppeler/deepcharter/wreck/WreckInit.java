package io.github.pkeppeler.deepcharter.wreck;

/**
 * Server entry point for the wreck feature, called from DeepCharter. Issues: #67.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class WreckInit {
	private WreckInit() {
	}

	public static void init() {
		WreckRegistry.register();
	}
}
