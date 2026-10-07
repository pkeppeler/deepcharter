package io.github.pkeppeler.deepcharter.market;

/**
 * Server entry point for the market feature, called from DeepCharter. Issues: #68.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class MarketInit {
	private MarketInit() {
	}

	public static void init() {
		MarketRegistry.register();
	}
}
