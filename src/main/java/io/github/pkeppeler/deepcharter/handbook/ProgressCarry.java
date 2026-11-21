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
 * What a player made before they had a charter goes into the charter they found or join (ADR 0065, #843). This is the one place
 * where that happens, so a later kind of personal knowledge is carried by adding it to {@link #carry}.
 *
 * <p>Founding takes the founder's directives, Notes and known recipes into the new charter. Joining, and reviving a dormant charter, take the joiner's Notes and known recipes into
 * the charter's, the Notes unread for the other members (ADR 0020); a joiner takes the charter's directive progress, so their own
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
                ServerPlayer online = server.getPlayerList().getPlayer(member);
                if (online != null) {
                    carry(server, charter, online, true);
                }
            }
        });
        CharterEvents.JOINED.register(EARLY, (server, charter, player) -> carryJoiner(server, charter, player));
        // A revival is a join: the dormant charter keeps its own directives.
        CharterEvents.REVIVED.register(EARLY, (server, charter, director) -> carryJoiner(server, charter, director));
    }

    private static void carryJoiner(MinecraftServer server, Charter charter, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            carry(server, charter, online, false);
        }
    }

    /**
     * Carries {@code player}'s personal progress into {@code charter}: their Notes and known recipes always, and their
     * directives only when {@code founding}. A vanilla criterion the player met since the last poll counts. Does nothing while the saved progress,
     * Notes or personal data are unreadable.
     */
    private static void carry(MinecraftServer server, Charter charter, ServerPlayer player, boolean founding) {
        if (!Charters.isReadable(server)) {
            return;
        }
        KnownRecipes.carry(server, charter.id(), player);
        PersonalHandbookData personal = PersonalHandbookData.get(server);
        HandbookProgressData directives = HandbookProgressData.get(server);
        NotesData notes = NotesData.get(server);
        if (!personal.isReadable() || !directives.isReadable() || !notes.isReadable()) {
            return;
        }
        UUID id = player.getUUID();
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
