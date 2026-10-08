package io.github.pkeppeler.deepcharter.client.terminal;

/**
 * Client entry point for the terminal feature, called from DeepCharterClient. Issues: #59, #72.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class TerminalClientInit {
	private TerminalClientInit() {
	}

	public static void init() {
		TerminalClientRegistry.register();
	}
}
