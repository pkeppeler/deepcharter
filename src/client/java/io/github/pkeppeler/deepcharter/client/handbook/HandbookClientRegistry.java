package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import io.github.pkeppeler.deepcharter.handbook.HandbookSyncPayload;

/** Receives the handbook sync, and forgets the progress when the client leaves a world. */
public final class HandbookClientRegistry {
	private HandbookClientRegistry() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(HandbookSyncPayload.TYPE, (payload, context) -> ClientHandbook.set(payload.completed()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientHandbook.set(List.of());
			ClientReadMarks.reset();
		});
		HandbookKeys.register();
	}
}
