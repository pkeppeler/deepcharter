package io.github.pkeppeler.deepcharter.terminal;

/**
 * Server entry point for the terminal feature, called from DeepCharter. Issues: #59, #72.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class TerminalInit {
	private TerminalInit() {
	}

	public static void init() {
		TerminalRegistry.register();
		TerminalActivity.init();
	}
}
