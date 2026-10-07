package io.github.pkeppeler.deepcharter.client.market;

import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/** The ore processor's screen, and the account HUD. */
public final class MarketClientRegistry {
	private MarketClientRegistry() {
	}

	public static void register() {
		TerminalScreens.register(TerminalTypes.ORE_PROCESSOR, OreProcessorScreen::new);
		AccountHud.init();
	}
}
