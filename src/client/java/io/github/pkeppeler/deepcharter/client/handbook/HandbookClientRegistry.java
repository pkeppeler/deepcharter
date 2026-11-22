package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueScreens;
import io.github.pkeppeler.deepcharter.handbook.HandbookSyncPayload;
import io.github.pkeppeler.deepcharter.handbook.NotesSyncPayload;
import io.github.pkeppeler.deepcharter.handbook.OpenNotePayload;
import io.github.pkeppeler.deepcharter.handbook.PencilSyncPayload;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.Identifier;

/** Receives the handbook, Notes and pencil path syncs, and forgets them when the client leaves a world. */
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
        ClientPlayNetworking.registerGlobalReceiver(
                PencilSyncPayload.TYPE, (payload, context) -> ClientPencil.set(payload.completed()));
        ClientPlayNetworking.registerGlobalReceiver(
                OpenNotePayload.TYPE,
                (payload, context) -> HandbookScreen.openNoteFromWorld(context.client(), payload.note()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientHandbook.forget();
            HandbookMemory.forgetSession();
            ClientNotes.set(List.of());
            ClientPencil.set(List.of());
        });
        HandbookKeys.register();
        CatalogueScreens.register();
    }
}
