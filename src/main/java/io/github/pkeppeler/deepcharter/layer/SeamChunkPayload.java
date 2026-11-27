package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Clientbound (#1185, ADR 0089): one chunk of {@code dimension}, with its light, for the background level a {@link SeamPreparePayload}
 * made. The packet inside is vanilla's own chunk packet, built the way {@code PlayerChunkSender} builds it, but it rides a custom
 * payload so the client's current level does not take it.
 */
public record SeamChunkPayload(ResourceKey<Level> dimension, ClientboundLevelChunkWithLightPacket chunk)
        implements CustomPacketPayload {
    public static final Type<SeamChunkPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "seam_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SeamChunkPayload> CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION),
            SeamChunkPayload::dimension,
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC,
            SeamChunkPayload::chunk,
            SeamChunkPayload::new);

    /** Registers the payload type; {@link SeamSender} sends it. */
    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    @Override
    public Type<SeamChunkPayload> type() {
        return TYPE;
    }
}
