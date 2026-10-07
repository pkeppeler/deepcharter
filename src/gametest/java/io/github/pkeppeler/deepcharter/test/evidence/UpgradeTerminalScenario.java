package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.UpgradeTerminalClientTest;

/**
 * Evidence scenario "m2-upgrade-terminal" for #73: a charter with a pod parked at the upgrade terminal reads the hull prices, buys a
 * tier 1 hull, then a tier 4 hull, which the Mole works at tier 2 and the screen says so. Stills of the prices and of the cap.
 */
public class UpgradeTerminalScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 3;
	private static final int TYPING_FRAMES = 30;
	private static final int HOLD_FRAMES = 8;
	private static final int WAIT_TICKS = 200;

	@Override
	protected String name() {
		return "m2-upgrade-terminal";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			UpgradeTerminalClientTest.Scene scene = singleplayer.getServer().computeOnServer(UpgradeTerminalClientTest::setUp);

			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.terminal())));
			context.waitForScreen(UpgradeScreen.class);
			UpgradeScreen screen = context.computeOnClient(client -> (UpgradeScreen) client.gui.screen());
			context.waitFor(client -> screen.upgrade().isPresent(), WAIT_TICKS);
			for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			hold(context);
			screenshot(context, "upgrade-terminal");

			context.clickScreenButton("HULL  T0");
			hold(context);
			screenshot(context, "hull-prices");

			context.clickScreenButton("BUY TIER 1  $750");
			context.waitFor(client -> screen.upgrade().get().pod().orElseThrow().slots().stream()
					.anyMatch(slot -> slot.installed() == 1), WAIT_TICKS);
			hold(context);

			context.clickScreenButton("BUY TIER 4  $20000  (WORKS AS TIER 2)");
			context.waitFor(client -> screen.upgrade().get().pod().orElseThrow().slots().stream()
					.anyMatch(slot -> slot.installed() == 4), WAIT_TICKS);
			hold(context);
			screenshot(context, "capped-hull");
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
