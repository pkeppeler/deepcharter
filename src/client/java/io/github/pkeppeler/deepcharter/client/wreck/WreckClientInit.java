package io.github.pkeppeler.deepcharter.client.wreck;

/**
 * Client entry point for the wreck feature, called from DeepCharterClient. Issues: #67.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class WreckClientInit {
	private WreckClientInit() {
	}

	public static void init() {
		WreckClientRegistry.register();
	}
}
