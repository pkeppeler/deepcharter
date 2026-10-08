package io.github.pkeppeler.deepcharter.client.charter;

import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import io.github.pkeppeler.deepcharter.charter.CharterSyncPayload;

/** Receives the charter sync, and forgets the charter when the client leaves a world. */
public final class CharterClientRegistry {
	private CharterClientRegistry() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(CharterSyncPayload.TYPE, (payload, context) -> ClientCharter.set(payload.charter()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientCharter.set(Optional.empty()));
	}
}
