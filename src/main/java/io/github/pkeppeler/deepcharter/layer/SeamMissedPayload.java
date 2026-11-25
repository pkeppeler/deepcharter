package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Serverbound (#1185, ADR 0089): the client crossed into {@code dimension} without the background level it was sent, so it holds
 * none of the chunks the server skipped at the crossing. The server sends them again.
 */
public record SeamMissedPayload(ResourceKey<Level> dimension) implements CustomPacketPayload {
    public static final Type<SeamMissedPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "seam_missed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SeamMissedPayload> CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION), SeamMissedPayload::dimension, SeamMissedPayload::new);

    /** Registers the payload type; {@link SeamSender} receives it. */
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
    }

    @Override
    public Type<SeamMissedPayload> type() {
        return TYPE;
    }
}
