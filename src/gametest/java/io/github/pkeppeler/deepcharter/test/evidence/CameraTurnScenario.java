package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Example scenario: load into a world and turn the camera through a full circle.
 *
 * To add a scenario: copy this class, change name() and run(), and list the class under
 * "fabric-client-gametest" in src/gametest/resources/fabric.mod.json.
 */
public class CameraTurnScenario extends EvidenceScenario {
	private static final int FRAMES = 45;
	private static final float DEGREES_PER_FRAME = 360f / FRAMES;

	@Override
	protected String name() {
		return "camera-turn";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			screenshot(context, "world-loaded");
			for (int i = 0; i < FRAMES; i++) {
				context.runOnClient(client -> client.player.setYRot(client.player.getYRot() + DEGREES_PER_FRAME));
				frame(context);
			}
		}
	}
}
