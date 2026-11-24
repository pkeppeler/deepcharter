package io.github.pkeppeler.deepcharter.layer;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;

/** Sends a rider the arrival area it is approaching, ahead of the crossing (#1185, ADR 0089). */
public final class SeamSender {
    private SeamSender() {}

    public static void init() {
        SeamPreparePayload.init();
        SeamChunkPayload.init();
        SeamDiscardPayload.init();
        SeamMissedPayload.init();
    }

    /** The chunk offsets of an area of {@code radius} chunks, nearest the centre first, in a fixed order. */
    public static List<ChunkPos> offsets(int radius) {
        return List.of();
    }

    /** The bytes a chunk packet is estimated to take on the wire. */
    public static int estimatedBytes(ClientboundLevelChunkWithLightPacket packet) {
        return 0;
    }
}
