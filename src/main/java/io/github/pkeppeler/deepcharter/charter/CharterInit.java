package io.github.pkeppeler.deepcharter.charter;

import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminalInit;

/**
 * Server entry point for the charter feature, called from DeepCharter. Issues: #52, #72.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class CharterInit {
	private CharterInit() {
	}

	public static void init() {
		CharterRegistry.register();
		CharterCommands.init();
		ContractTerminalInit.init();
	}
}
