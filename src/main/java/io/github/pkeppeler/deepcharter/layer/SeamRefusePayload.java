package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Serverbound (#1229, ADR 0089): the client cannot take a swap into {@code dimension} for the prepare of this
 * {@code generation}, for {@code reason}. A refusal withdraws an accept of that generation.
 */
public record SeamRefusePayload(ResourceKey<Level> dimension, int generation, SeamRefuseReason reason)
        implements CustomPacketPayload {
    public static final Type<SeamRefusePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "seam_refuse"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SeamRefusePayload> CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION),
            SeamRefusePayload::dimension,
            ByteBufCodecs.VAR_INT,
            SeamRefusePayload::generation,
            SeamRefuseReason.CODEC,
            SeamRefusePayload::reason,
            SeamRefusePayload::new);

    /** Registers the payload type; {@link SeamSender} receives it. */
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
    }

    @Override
    public Type<SeamRefusePayload> type() {
        return TYPE;
    }
}
