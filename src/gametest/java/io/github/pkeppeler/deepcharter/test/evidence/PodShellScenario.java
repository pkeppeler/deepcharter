package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.PodShellClientTest;

/** Evidence scenario "pod-shell": a screenshot of the pod and its rider. */
public class PodShellScenario extends EvidenceScenario {
	@Override
	protected String name() {
		return "pod-shell";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			PodShellClientTest.mountFirstPlayer(singleplayer.getServer());
			context.waitFor(client -> client.player.getVehicle() instanceof PodEntity);
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			context.waitTicks(20);
			screenshot(context, "pod-rider-third-person");
			frame(context);
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			context.waitTicks(10);
			screenshot(context, "pod-rider-hud");
			frame(context);
		}
	}
}
