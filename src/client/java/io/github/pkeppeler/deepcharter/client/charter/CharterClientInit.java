package io.github.pkeppeler.deepcharter.client.charter;

/**
 * Client entry point for the charter feature, called from DeepCharterClient. Issues: #52.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class CharterClientInit {
	private CharterClientInit() {
	}

	public static void init() {
		CharterClientRegistry.register();
	}
}
