package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.input.CharacterEvent;

import io.github.pkeppeler.deepcharter.client.ui.CrtDemoScreen;

/**
 * Evidence scenario "m2-crt-ui-kit" for #53: the demo screen types its text out, then a name is typed into the
 * field. The title screen is enough: the kit needs no world.
 */
public class CrtUiKitScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 3;
	private static final int TYPING_FRAMES = 40;
	private static final int END_FRAMES = 6;

	@Override
	protected String name() {
		return "m2-crt-ui-kit";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		context.setScreen(CrtDemoScreen::new);
		context.waitForScreen(CrtDemoScreen.class);
		CrtDemoScreen screen = context.computeOnClient(client -> (CrtDemoScreen) client.gui.screen());
		for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
		context.runOnClient(client -> "RIGGS".chars().forEach(c -> screen.charTyped(new CharacterEvent(c))));
		for (int i = 0; i < END_FRAMES; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
		screenshot(context, "crt-ui-kit");
		context.setScreen(() -> null);
	}
}
