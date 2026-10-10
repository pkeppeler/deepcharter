package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene.Scene;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScreens;

/**
 * Evidence scenario "terminal-panel" for #246 (docs/design/terminal-concepts.md): the seven terminal screens as a player sees them, with no test
 * pack. Stills are named {@code <screen>}: hangar, processor, upgrade, repair, pump, contract and generic. The first five are shot in one world, the
 * contract terminal in another where the player has no charter, and the generic (offline) terminal in a third. The recording holds every screen for a few frames.
 */
public class TerminalPanelScenario extends EvidenceScenario {
	private static final int HOLD_FRAMES = 4;
	private static final int TICKS_PER_FRAME = 3;
	/** Where the cursor is parked: well outside the window, so that no button is hovered. */
	private static final double OFF_SCREEN = -10_000;

	@Override
	protected String name() {
		return "terminal-panel";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUp);
			ClientWait.until(context, "the charter's account", client -> ClientCharter.view().map(charter -> charter.balance() == TerminalPanelScene.ACCOUNT).orElse(false),
					client -> "charter " + ClientCharter.view());
			for (String screen : TerminalPanelScreens.SCENE_SCREENS) {
				shoot(context, screen, scene, null);
			}
		}
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			BlockPos contract = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUpContract);
			shoot(context, TerminalPanelScreens.CONTRACT, null, contract);
		}
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			BlockPos offline = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUpOffline);
			shoot(context, TerminalPanelScreens.GENERIC, null, offline);
		}
	}

	private void shoot(ClientGameTestContext context, String screen, Scene scene, BlockPos elsewhere) {
		TerminalPanelScreens.open(context, screen, scene, elsewhere);
		context.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN);
		for (int i = 0; i < HOLD_FRAMES; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
		screenshot(context, screen);
		TerminalPanelScreens.close(context);
	}
}
