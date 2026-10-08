package io.github.pkeppeler.deepcharter.client.transmission;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import io.github.pkeppeler.deepcharter.transmission.TransmissionCatalog;
import io.github.pkeppeler.deepcharter.transmission.TransmissionPayload;

/** Receives transmissions into the overlay, ticks it, draws it, and clears it when the client leaves a world. */
public final class TransmissionClientRegistry {
	private TransmissionClientRegistry() {
	}

	public static void register() {
		// Read the data file now: a broken file stops the game at start-up, never in a packet handler.
		TransmissionCatalog.all();
		ClientPlayNetworking.registerGlobalReceiver(TransmissionPayload.TYPE, (payload, context) -> TransmissionOverlay.enqueue(payload));
		ClientTickEvents.END_CLIENT_TICK.register(client -> TransmissionOverlay.tick());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TransmissionOverlay.reset());
		TransmissionHud.init();
	}
}
