package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Clientbound (#1229, ADR 0089): the vehicle of the receiving rider moves into the held level of {@code dimension}
 * (of this {@code generation}) with no respawn, to {@code arrival}. It is the first packet of the swap, ahead of any
 * packet about the new level. {@code rootId} and {@code rootUuid} name the root vehicle and {@code carried} the entity
 * ids of its whole tree, root included, which the client checks it holds before it swaps.
 */
public record SeamSwapPayload(
        ResourceKey<Level> dimension, int generation, int rootId, UUID rootUuid, Vec3 arrival, List<Integer> carried)
        implements CustomPacketPayload {
    public static final Type<SeamSwapPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "seam_swap"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SeamSwapPayload> CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION),
            SeamSwapPayload::dimension,
            ByteBufCodecs.VAR_INT,
            SeamSwapPayload::generation,
            ByteBufCodecs.VAR_INT,
            SeamSwapPayload::rootId,
            UUIDUtil.STREAM_CODEC,
            SeamSwapPayload::rootUuid,
            Vec3.STREAM_CODEC,
            SeamSwapPayload::arrival,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()),
            SeamSwapPayload::carried,
            SeamSwapPayload::new);

    /** Registers the payload type; {@link SeamSwap} sends it. */
    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    @Override
    public Type<SeamSwapPayload> type() {
        return TYPE;
    }
}
