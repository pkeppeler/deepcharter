package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.FuelPumpClientTest;

/**
 * Evidence scenario "m2-fuel-pump" for #69: a charter with $20 opens the pump beside its pod, which has 2 of 10 litres, buys 5
 * litres and then fills up. The account goes down a dollar a litre and the tank goes up.
 */
public class FuelPumpScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 3;
	private static final int TYPING_FRAMES = 45;
	private static final int HOLD_FRAMES = 8;
	private static final int WAIT_TICKS = 200;

	@Override
	protected String name() {
		return "m2-fuel-pump";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			FuelPumpClientTest.Scene scene = singleplayer.getServer().computeOnServer(FuelPumpClientTest::setUp);
			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.pump())));
			context.waitForScreen(FuelPumpScreen.class);
			FuelPumpScreen screen = context.computeOnClient(client -> (FuelPumpScreen) client.gui.screen());
			context.waitFor(client -> ClientCharter.view().isPresent() && !Terminals.parkedPods(client.level, scene.pump()).isEmpty(), WAIT_TICKS);
			for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			hold(context);
			screenshot(context, "pump-before");

			long start = FuelPumpClientTest.START_BALANCE;
			context.clickScreenButton("BUY 5 L");
			context.waitFor(client -> ClientCharter.view().get().balance() == start - 5, WAIT_TICKS);
			hold(context);
			screenshot(context, "pump-bought-5");

			context.clickScreenButton("FILL UP");
			context.waitFor(client -> ClientCharter.view().get().balance() == start - 8, WAIT_TICKS);
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
