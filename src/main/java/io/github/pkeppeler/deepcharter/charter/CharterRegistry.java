package io.github.pkeppeler.deepcharter.charter;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Registers the charter sync payload and tells each joining player which charter they are on. The SavedData needs no registration. */
public final class CharterRegistry {
	private CharterRegistry() {
	}

	public static void register() {
		CharterSyncPayload.register();
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> CharterSyncPayload.send(server, handler.getPlayer()));
	}
}
