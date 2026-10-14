package io.github.pkeppeler.deepcharter.charter;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Registers the charter sync payload and tells each joining player which charter they are on. It also polls how deep each member is ({@link DeepestPoint}). The SavedData needs no registration. */
public final class CharterRegistry {
    private CharterRegistry() {}

    public static void register() {
        CharterSyncPayload.register();
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> CharterSyncPayload.send(server, handler.getPlayer()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % CharterTuning.DEFAULT.deepestPointPollTicks() == 0) {
                DeepestPoint.poll(server);
            }
        });
    }
}
