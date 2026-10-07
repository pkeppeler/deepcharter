package io.github.pkeppeler.deepcharter.charter.terminal;

/**
 * Server entry point for the contract terminal, called from {@code CharterInit}. Issue: #72.
 * Each part of the contract terminal registers itself from here, one line per part.
 */
public final class ContractTerminalInit {
	private ContractTerminalInit() {
	}

	public static void init() {
		ContractTerminalRegistry.register();
	}
}
