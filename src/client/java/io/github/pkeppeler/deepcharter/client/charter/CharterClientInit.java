package io.github.pkeppeler.deepcharter.client.charter;

import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractTerminalClientInit;

/**
 * Client entry point for the charter feature, called from DeepCharterClient. Issues: #52, #72.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class CharterClientInit {
	private CharterClientInit() {
	}

	public static void init() {
		CharterClientRegistry.register();
		ContractTerminalClientInit.init();
	}
}
