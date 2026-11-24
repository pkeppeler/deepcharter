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
 * Serverbound (#1229, ADR 0089): the client built, or kept, the held level of the {@link SeamPreparePayload} of this
 * {@code generation} for {@code dimension}, so it can take a swap into it. An accept of an older generation is not one.
 */
public record SeamAcceptPayload(ResourceKey<Level> dimension, int generation) implements CustomPacketPayload {
    public static final Type<SeamAcceptPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "seam_accept"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SeamAcceptPayload> CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION),
            SeamAcceptPayload::dimension,
            ByteBufCodecs.VAR_INT,
            SeamAcceptPayload::generation,
            SeamAcceptPayload::new);

    /** Registers the payload type; {@link SeamSender} receives it. */
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
    }

    @Override
    public Type<SeamAcceptPayload> type() {
        return TYPE;
    }
}
