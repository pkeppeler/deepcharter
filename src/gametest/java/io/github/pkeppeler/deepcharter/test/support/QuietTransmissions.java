package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.transmission.TransmissionCatalog;
import io.github.pkeppeler.deepcharter.transmission.TransmissionData;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps the transmission panel off the screen of a client test about something else. Founding a charter sends T01, and standing in a
 * layer's zones sends more, each a panel over the middle of the view that a screenshot would catch.
 */
public final class QuietTransmissions {
    private QuietTransmissions() {}

    /**
     * On the server: marks every transmission fired for {@code member}'s charter and sent to {@code member}, so nothing more reaches them,
     * and pays no bonus.
     */
    public static void silence(MinecraftServer server, ServerPlayer member) {
        CharterId charter = Charters.charterOfOrThrow(server, member.getUUID())
                .orElseThrow()
                .id();
        TransmissionData data = TransmissionData.get(server);
        TransmissionCatalog.all().forEach(transmission -> data.fire(charter, transmission));
        data.markSent(charter, member.getUUID(), data.progress(charter).fired().size());
    }

    /** On the client: clears what is on the panel and queued, which {@link #silence} cannot recall once it was sent. */
    public static void clear(ClientGameTestContext context) {
        context.runOnClient(client -> TransmissionOverlay.reset());
    }
}
