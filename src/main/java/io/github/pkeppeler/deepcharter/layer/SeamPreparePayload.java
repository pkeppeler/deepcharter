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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Clientbound (#1185, ADR 0089): a rider's vehicle nears the floor of {@code dimension}, and its arrival chunks come
 * next. The client builds the background level they go into from the fields of the respawn it will adopt it at: the
 * dimension, its type, the obfuscated seed, the sea level, and whether it is flat or a debug world. {@code centre} is
 * where the level's view is centred. {@code generation} counts the rider's prepares (#1229): the client's accept or
 * refusal names it, so one of an older prepare is not taken for the current one.
 */
public record SeamPreparePayload(
        ResourceKey<Level> dimension,
        ResourceKey<DimensionType> dimensionType,
        long seedHash,
        int seaLevel,
        boolean flat,
        boolean debug,
        ChunkPos centre,
        int generation)
        implements CustomPacketPayload {
    public static final Type<SeamPreparePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "seam_prepare"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SeamPreparePayload> CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION),
            SeamPreparePayload::dimension,
            ResourceKey.streamCodec(Registries.DIMENSION_TYPE),
            SeamPreparePayload::dimensionType,
            ByteBufCodecs.LONG,
            SeamPreparePayload::seedHash,
            ByteBufCodecs.VAR_INT,
            SeamPreparePayload::seaLevel,
            ByteBufCodecs.BOOL,
            SeamPreparePayload::flat,
            ByteBufCodecs.BOOL,
            SeamPreparePayload::debug,
            ChunkPos.STREAM_CODEC,
            SeamPreparePayload::centre,
            ByteBufCodecs.VAR_INT,
            SeamPreparePayload::generation,
            SeamPreparePayload::new);

    /** Registers the payload type; {@link SeamSender} sends it. */
    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    @Override
    public Type<SeamPreparePayload> type() {
        return TYPE;
    }
}
