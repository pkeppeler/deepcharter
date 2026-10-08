package io.github.pkeppeler.deepcharter.client.upgrade;

import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/** Registers the upgrade terminal's online screen. */
public final class UpgradeClientRegistry {
	private UpgradeClientRegistry() {
	}

	public static void register() {
		TerminalScreens.register(TerminalTypes.UPGRADE_TERMINAL, UpgradeScreen::new);
	}
}
