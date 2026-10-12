package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.UpgradeTerminalClientTest;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

/**
 * Evidence scenario "m2-upgrade-terminal" for #73: a charter with a pod parked at the upgrade terminal reads the hull prices, buys a
 * tier 2 hull (tier 1 is crafted, not sold), then a tier 4 hull, which the Mole works at tier 2 and the screen says so. Stills of the prices and of the cap.
 */
public class UpgradeTerminalScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 3;
	private static final int TYPING_FRAMES = 30;
	private static final int HOLD_FRAMES = 8;

	@Override
	protected String name() {
		return "m2-upgrade-terminal";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			UpgradeTerminalClientTest.Scene scene = singleplayer.getServer().computeOnServer(UpgradeTerminalClientTest::setUp);

			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.terminal())));
			ClientWait.screen(context, UpgradeScreen.class);
			UpgradeScreen screen = context.computeOnClient(client -> (UpgradeScreen) client.gui.screen());
			for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			hold(context);
			screenshot(context, "upgrade-terminal");

			context.clickScreenButton("HULL  T0");
			hold(context);
			screenshot(context, "hull-prices");

			// Tier 1 is crafted and not sold (#536): the row says so, and the first purchase is tier 2.
			context.clickScreenButton("BUY TIER 2  $500");
			ClientWait.until(context, "a part installed at tier 2", client -> screen.upgrade().orElseThrow().pod().orElseThrow().slots().stream()
					.anyMatch(slot -> slot.installed() == 2));
			hold(context);

			context.clickScreenButton("BUY TIER 4  $5000  (WORKS AS TIER 2)");
			ClientWait.until(context, "a part installed at tier 4", client -> screen.upgrade().orElseThrow().pod().orElseThrow().slots().stream()
					.anyMatch(slot -> slot.installed() == 4));
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
