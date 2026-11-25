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
 * Clientbound (#1185, ADR 0089): the background level of {@code dimension} is not needed any more (the vehicle left its zone), so the
 * client lets it go.
 */
public record SeamDiscardPayload(ResourceKey<Level> dimension) implements CustomPacketPayload {
    public static final Type<SeamDiscardPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "seam_discard"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SeamDiscardPayload> CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION), SeamDiscardPayload::dimension, SeamDiscardPayload::new);

    /** Registers the payload type; {@link SeamSender} sends it. */
    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    @Override
    public Type<SeamDiscardPayload> type() {
        return TYPE;
    }
}
