package io.github.pkeppeler.deepcharter.client.continuity;

import io.github.pkeppeler.deepcharter.continuity.ContinuityEventPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client entry point for the Continuity Event (#1337), called from DeepCharterClient: the numbers from the server, the ear that keeps the
 * last few seconds of sound, the sequence's tick, and a clean slate on leaving a world. The screen, the silence and the still body are mixins in {@code
 * client/continuity/mixin} (ADR 0100).
 */
public final class ContinuityClientInit {
    private ContinuityClientInit() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(
                ContinuityEventPayload.TYPE, (payload, context) -> ContinuityClient.received(payload));
        ClientLifecycleEvents.CLIENT_STARTED.register(
                client -> client.getSoundManager().addListener(RecentSounds.INSTANCE));
        ClientTickEvents.END_CLIENT_TICK.register(ContinuityClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ContinuityClient.forget();
            RecentSounds.INSTANCE.clear();
        });
    }
}
