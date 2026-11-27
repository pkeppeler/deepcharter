package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The alert for a visit: "Disturbance reported at Outpost N." to the charter's members who are online, wherever they are. You know, and
 * you cannot see. Members who are offline get nothing later: the incident report covers them ({@link IncidentReports}).
 */
final class VisitAlerts {
    private VisitAlerts() {}

    /** Tells the online members of the outpost's charter, once. A charter with no readable record or no Director is told nothing. */
    static void tell(MinecraftServer server, Outpost outpost) {
        Component message = Component.translatable("message.deepcharter.outpost.disturbance", outpost.layer());
        for (Charter charter : Charters.readableFind(server, outpost.charter()).stream()
                .filter(found -> !found.dormant())
                .toList()) {
            for (var member : charter.roster()) {
                ServerPlayer player = server.getPlayerList().getPlayer(member);
                if (player != null) {
                    player.sendSystemMessage(message);
                }
            }
        }
    }
}
