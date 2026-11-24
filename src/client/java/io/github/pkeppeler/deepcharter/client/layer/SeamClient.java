package io.github.pkeppeler.deepcharter.client.layer;

import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;

/** The client's half of a seam (#1185, ADR 0089): the background level the server's arrival data goes into, adopted at the crossing. */
public final class SeamClient {
    private SeamClient() {}

    public static void init() {}

    /** Bytes of arrival chunks the client has been sent ahead in this session, for the timing log of the client test. */
    // test-seam: SeamAdoptionClientTest logs it in its SEAM-TIMING line
    public static long bytesReceived() {
        return 0;
    }

    /** The level holding the arrival area that is not the one the client shows, if the server prepared one. */
    // test-seam: SeamAdoptionClientTest reads the held level, to show that the crossing adopts it and not a fresh one
    public static Optional<ClientLevel> background() {
        return Optional.empty();
    }
}
