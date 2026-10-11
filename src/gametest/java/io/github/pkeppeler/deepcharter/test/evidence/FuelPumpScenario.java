package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.FuelPumpClientTest;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

/**
 * Evidence scenario "m2-fuel-pump" for #69: a charter with $20 opens the pump beside its pod, which has 2 of 10 litres, buys 5
 * litres and then fills up. The account goes down a dollar a litre and the tank goes up.
 */
public class FuelPumpScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 3;
	private static final int TYPING_FRAMES = 45;
	private static final int HOLD_FRAMES = 8;

	@Override
	protected String name() {
		return "m2-fuel-pump";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			FuelPumpClientTest.Scene scene = singleplayer.getServer().computeOnServer(FuelPumpClientTest::setUp);
			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.pump())));
			ClientWait.screen(context, FuelPumpScreen.class);
			FuelPumpScreen screen = context.computeOnClient(client -> (FuelPumpScreen) client.gui.screen());
			ClientWait.until(context, "the charter view and a pod parked at the pump", client -> ClientCharter.view().isPresent() && !Terminals.parkedPods(client.level, scene.pump()).isEmpty(),
					client -> "charter " + ClientCharter.view().isPresent() + ", parked pods " + Terminals.parkedPods(client.level, scene.pump()).size());
			for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			hold(context);
			screenshot(context, "pump-before");

			long start = FuelPumpClientTest.START_BALANCE;
			context.clickScreenButton("BUY 5 L");
			ClientWait.until(context, "the charter's balance of " + (start - 5),
					client -> ClientCharter.view().map(view -> view.balance() == start - 5).orElse(false),
					client -> "the charter's balance of " + ClientCharter.view().map(view -> String.valueOf(view.balance())).orElse("(none)"));
			hold(context);
			screenshot(context, "pump-bought-5");

			context.clickScreenButton("FILL UP");
			ClientWait.until(context, "the charter's balance of " + (start - 8),
					client -> ClientCharter.view().map(view -> view.balance() == start - 8).orElse(false),
					client -> "the charter's balance of " + ClientCharter.view().map(view -> String.valueOf(view.balance())).orElse("(none)"));
			hold(context);
			screenshot(context, "pump-full");
			context.setScreen(() -> null);
		}
	}

	private void hold(ClientGameTestContext context) {
		for (int i = 0; i < HOLD_FRAMES; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}
}
