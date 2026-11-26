package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.test.mixin.ServerChunkCacheAccess;
import io.github.pkeppeler.deepcharter.test.mixin.TicketStorageAccess;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;

/** The chunk tickets vanilla holds for a level, read from its ticket storage and counted by type. */
public final class ServerTickets {
    private ServerTickets() {}

    /** The tickets of {@code level} whose type {@code counted} accepts, by type id, in id order. */
    public static Map<String, Integer> byType(ServerLevel level, Predicate<TicketType> counted) {
        TicketStorageAccess storage =
                (TicketStorageAccess) ((ServerChunkCacheAccess) level.getChunkSource()).deepcharter$ticketStorage();
        Map<String, Integer> counts = new TreeMap<>();
        for (List<Ticket> tickets : storage.deepcharter$tickets().values()) {
            for (Ticket ticket : tickets) {
                if (counted.test(ticket.getType())) {
                    counts.merge(
                            String.valueOf(BuiltInRegistries.TICKET_TYPE.getKey(ticket.getType())), 1, Integer::sum);
                }
            }
        }
        return counts;
    }
}
