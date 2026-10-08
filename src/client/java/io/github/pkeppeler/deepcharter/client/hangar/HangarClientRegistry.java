package io.github.pkeppeler.deepcharter.client.hangar;

import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;

/** Registers the online screen of the hangar console. */
public final class HangarClientRegistry {
	private HangarClientRegistry() {
	}

	public static void register() {
		TerminalScreens.register(HangarTerminal.TYPE, HangarScreen::new);
	}
}
