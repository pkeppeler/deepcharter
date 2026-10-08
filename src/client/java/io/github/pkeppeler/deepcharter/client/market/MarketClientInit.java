package io.github.pkeppeler.deepcharter.client.market;

/**
 * Client entry point for the market feature, called from DeepCharterClient. Issues: #68.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class MarketClientInit {
	private MarketClientInit() {
	}

	public static void init() {
		MarketClientRegistry.register();
	}
}
