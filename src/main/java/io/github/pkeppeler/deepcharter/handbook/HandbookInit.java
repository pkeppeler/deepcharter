package io.github.pkeppeler.deepcharter.handbook;

/**
 * Server entry point for the handbook feature, called from DeepCharter. Issues: #61, #66, #78, #81, #84.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class HandbookInit {
	private HandbookInit() {
	}

	public static void init() {
		HandbookRegistry.register();
		HandbookCommands.init();
	}
}
