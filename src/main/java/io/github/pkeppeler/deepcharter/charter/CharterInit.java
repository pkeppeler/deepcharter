package io.github.pkeppeler.deepcharter.charter;

/**
 * Server entry point for the charter feature, called from DeepCharter. Issues: #52.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class CharterInit {
	private CharterInit() {
	}

	public static void init() {
		CharterRegistry.register();
		CharterCommands.init();
	}
}
