package io.github.pkeppeler.deepcharter.client.layer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import io.github.pkeppeler.deepcharter.layer.BreachPayload;

public final class LayerClientRegistry {
	private LayerClientRegistry() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(BreachPayload.TYPE, (payload, context) -> BreachEffects.begin());
		ClientTickEvents.END_CLIENT_TICK.register(client -> BreachEffects.tick());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BreachEffects.reset());
		BreachHud.init();
		BreachVoidCover.init();
	}
}
