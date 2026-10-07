package io.github.pkeppeler.deepcharter.client.charter.terminal;

/**
 * Client entry point for the contract terminal, called from {@code CharterClientInit}. Issue: #72.
 * Each part of the contract terminal registers itself from here, one line per part.
 */
public final class ContractTerminalClientInit {
	private ContractTerminalClientInit() {
	}

	public static void init() {
		ContractTerminalClientRegistry.register();
	}
}
