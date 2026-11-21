package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.handbook.HandbookSyncPayload;
import io.github.pkeppeler.deepcharter.handbook.NotesSyncPayload;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.Identifier;

/** Receives the handbook and Notes syncs, and forgets them when the client leaves a world. */
public final class HandbookClientRegistry {
    private HandbookClientRegistry() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(HandbookSyncPayload.TYPE, (payload, context) -> {
            for (Identifier done : ClientHandbook.set(payload.completed())) {
                StepToast.show(context.client(), done);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(
                NotesSyncPayload.TYPE, (payload, context) -> ClientNotes.set(payload.found()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientHandbook.forget();
            HandbookMemory.forgetSession();
            ClientNotes.set(List.of());
        });
        HandbookKeys.register();
    }
}
