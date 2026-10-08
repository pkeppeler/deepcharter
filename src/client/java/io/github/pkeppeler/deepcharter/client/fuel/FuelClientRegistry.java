package io.github.pkeppeler.deepcharter.client.fuel;

import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/** Registers the fuel pump's screen. */
public final class FuelClientRegistry {
	private FuelClientRegistry() {
	}

	public static void register() {
		TerminalScreens.register(TerminalTypes.FUEL_PUMP, FuelPumpScreen::new);
	}
}
