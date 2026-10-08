package io.github.pkeppeler.deepcharter.client.pod;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import io.github.pkeppeler.deepcharter.pod.PodEntity;

/**
 * The pod shields its pilot from lava on the server (#288), but the client also simulates lava on its own player and lights it,
 * which draws flames on the pilot. While lava burns the pod's hull the local pilot's fire is put out each client tick, so the
 * only cue is the HUD line, the hiss and the hull number.
 */
public final class PilotFire {
	private PilotFire() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player != null && client.player.getVehicle() instanceof PodEntity pod && pod.hullBurning()) {
				client.player.clearFire();
			}
		});
	}
}
