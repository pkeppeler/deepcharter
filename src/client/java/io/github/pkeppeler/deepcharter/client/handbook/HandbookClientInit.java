package io.github.pkeppeler.deepcharter.client.handbook;

/**
 * Client entry point for the handbook feature, called from DeepCharterClient. Issues: #61, #66, #78, #81, #84.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class HandbookClientInit {
	private HandbookClientInit() {
	}

	public static void init() {
		HandbookClientRegistry.register();
	}
}
