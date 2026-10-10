package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.PodShellClientTest;

/** Evidence scenario "pod-drive": a bot-driven recording of a pod driving along the ground, climbing on its rotor and falling back. */
public class PodDriveScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 2;
	private static final int DRIVE_FRAMES = 15;
	private static final int CLIMB_FRAMES = 20;
	private static final int FALL_FRAMES = 15;

	@Override
	protected String name() {
		return "pod-drive";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			PodShellClientTest.mountFirstPlayer(singleplayer.getServer());
			context.waitFor(client -> client.player.getVehicle() instanceof PodEntity);
			// The shared mount helper starts the pod damaged; the picture is of a sound pod.
			singleplayer.getServer().runOnServer(server -> {
				PodEntity pod = (PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle();
				pod.setHull(pod.maxHull());
			});
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			context.waitTicks(20);
			frame(context);

			context.getInput().holdKey(options -> options.keyUp);
			record(context, DRIVE_FRAMES);
			context.getInput().holdKey(options -> options.keyJump);
			record(context, CLIMB_FRAMES);
			screenshot(context, "pod-flying");
			context.getInput().releaseKey(options -> options.keyJump);
			context.getInput().releaseKey(options -> options.keyUp);
			record(context, FALL_FRAMES);
			screenshot(context, "pod-landed");
		}
	}

	private void record(ClientGameTestContext context, int frames) {
		for (int i = 0; i < frames; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}
}
