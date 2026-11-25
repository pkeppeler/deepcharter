package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Clientbound: offer the spec sheet of this item, because the player crafted or bought it for the first time
 * ({@link SpecSheetPopups}). The sheet itself is not sent: the client builds it from the same tunings.
 */
public record SpecSheetPayload(Identifier item) implements CustomPacketPayload {
    public static final Type<SpecSheetPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "spec_sheet"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpecSheetPayload> CODEC =
            StreamCodec.composite(Identifier.STREAM_CODEC, SpecSheetPayload::item, SpecSheetPayload::new);

    /** Registers the payload type. */
    static void register() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    /** Whether {@code player} can be sent a sheet: a client without this mod has no payload channel for it. */
    static boolean canSend(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, TYPE);
    }

    /** Tells {@code player}, whom {@link #canSend} allows, to offer the sheet. */
    static void send(ServerPlayer player, SpecSheetPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public Type<SpecSheetPayload> type() {
        return TYPE;
    }
}
