package io.github.pkeppeler.deepcharter.client.repair;

import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/** Registers the repair station's screen. */
public final class RepairClientRegistry {
	private RepairClientRegistry() {
	}

	public static void register() {
		TerminalScreens.register(TerminalTypes.REPAIR_STATION, RepairStationScreen::new);
	}
}
