package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene.Scene;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScreens;

/**
 * Evidence scenario "terminal-panel-use" for #246: two terminals used on the machine panel. At the upgrade terminal the hull track is chosen
 * (its row lights as a hover does) and a tier bought. At the repair station 25 points of hull are bought. The GIF is the whole run.
 */
public class TerminalPanelUseScenario extends EvidenceScenario {
	private static final int HOLD_FRAMES = 5;
	private static final int TICKS_PER_FRAME = 3;
	private static final int SETTLE_TICKS = 12;

	@Override
	protected String name() {
		return "terminal-panel-use";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUp);
			ClientWait.until(context, "the charter's account", client -> ClientCharter.view().map(charter -> charter.balance() == TerminalPanelScene.ACCOUNT).orElse(false),
					client -> "charter " + ClientCharter.view());
			UpgradeScreen upgrade = (UpgradeScreen) TerminalPanelScreens.open(context, "upgrade", scene, null);
			hold(context);
			context.clickScreenButton("HULL  T0");
			ClientWait.until(context, "the hull track chosen", client -> upgrade.selected().id().equals("hull"));
			hold(context);
			screenshot(context, "upgrade-hull");
			context.clickScreenButton("BUY TIER 1  $200");
			ClientWait.until(context, "tier 1 installed", client -> upgrade.upgrade().flatMap(view -> view.pod())
					.map(pod -> pod.slots().stream().anyMatch(slot -> slot.installed() == 1)).orElse(false));
			hold(context);
			TerminalPanelScreens.close(context);
			TerminalPanelScreens.open(context, "repair", scene, null);
			hold(context);
			context.clickScreenButton("REPAIR 25 HP ($25)");
			context.waitTicks(SETTLE_TICKS); // tick-wait: lets the server's answer reach the screen and the account line change
			hold(context);
			screenshot(context, "repair-bought");
			TerminalPanelScreens.close(context);
		}
	}

	private void hold(ClientGameTestContext context) {
		for (int i = 0; i < HOLD_FRAMES; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}
}
