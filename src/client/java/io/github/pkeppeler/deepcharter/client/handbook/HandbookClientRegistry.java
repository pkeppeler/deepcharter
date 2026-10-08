package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import io.github.pkeppeler.deepcharter.handbook.HandbookSyncPayload;
import io.github.pkeppeler.deepcharter.handbook.NotesSyncPayload;

/** Receives the handbook and Notes syncs, and forgets them when the client leaves a world. */
public final class HandbookClientRegistry {
	private HandbookClientRegistry() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(HandbookSyncPayload.TYPE, (payload, context) -> ClientHandbook.set(payload.completed()));
		ClientPlayNetworking.registerGlobalReceiver(NotesSyncPayload.TYPE, (payload, context) -> ClientNotes.set(payload.found()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientHandbook.set(List.of());
			ClientNotes.set(List.of());
		});
		HandbookKeys.register();
	}
}
