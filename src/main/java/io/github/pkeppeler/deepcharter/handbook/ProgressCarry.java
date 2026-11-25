package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterEvents;
import io.github.pkeppeler.deepcharter.charter.Charters;
import java.util.UUID;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * What a player made before they had a charter goes into the charter they found or join (ADR 0065, #843). This is the one
 * place where that happens, so a later kind of personal knowledge is carried by adding it to {@link #carry}.
 *
 * <p>Founding takes the founder's directives, Notes, known recipes and pencil paths into the new charter. Joining, and reviving a dormant
 * charter, take only the joiner's Notes, known recipes and pencil paths, the Notes unread for the other members (ADR 0020): a joiner takes
 * the charter's directive progress, so their own
 * completes none of its steps. The personal record stays, and is what the player has again if they leave.
 *
 * <p>The listeners run in {@link #EARLY}, before the ones that mirror advancements and sync clients, so those see the carried
 * progress.
 */
final class ProgressCarry {
    private static final Identifier EARLY = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "progress_carry");

    private ProgressCarry() {}

    static void register() {
        CharterEvents.FOUNDED.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
        CharterEvents.JOINED.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
        CharterEvents.REVIVED.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
        CharterEvents.FOUNDED.register(EARLY, (server, charter) -> {
            for (UUID member : charter.roster()) {
                carry(server, charter, member, true);
            }
        });
        CharterEvents.JOINED.register(EARLY, (server, charter, player) -> carry(server, charter, player, false));
        // A revival is a join: the dormant charter keeps its own directives.
        CharterEvents.REVIVED.register(EARLY, (server, charter, director) -> carry(server, charter, director, false));
    }

    /**
     * Carries an online player's personal progress into {@code charter}: their Notes, known recipes and completed pencil paths always, and their
     * directives only when {@code founding}. A vanilla criterion met since the last poll counts. Does nothing while any saved
     * data is unreadable.
     */
    private static void carry(MinecraftServer server, Charter charter, UUID id, boolean founding) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null || !Charters.isReadable(server)) {
            return;
        }
        KnownRecipes.carry(server, charter.id(), player);
        PencilPaths.carry(server, charter.id(), id);
        PersonalHandbookData personal = PersonalHandbookData.get(server);
        HandbookProgressData directives = HandbookProgressData.get(server);
        NotesData notes = NotesData.get(server);
        if (!personal.isReadable() || !directives.isReadable() || !notes.isReadable()) {
            return;
        }
        HandbookProgress.recordPersonal(server, player);
        for (Identifier note : personal.notes(id)) {
            notes.add(charter.id(), note);
        }
        if (founding) {
            for (Identifier directive : personal.directives(id)) {
                directives.complete(charter.id(), directive);
            }
        }
    }
}
