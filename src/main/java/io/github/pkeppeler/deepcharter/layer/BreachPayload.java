package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Clientbound: the receiving player has just crossed a breach, from layer {@code fromLayer} to {@code toLayer}. The
 * client answers with a rumble, a fade and a transmission. This is the only custom payload in M1. {@code fades} is
 * false for a crossing inside one level (the campaign world), where nothing was moved and so nothing needs hiding: the
 * client plays the rumble only. {@code swapped} is true for a crossing that moved the vehicle with no respawn (#1229):
 * it rumbles and never fades, and no respawn follows it.
 */
public record BreachPayload(int fromLayer, int toLayer, boolean fades, boolean swapped) implements CustomPacketPayload {
    public static final Type<BreachPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "breach"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BreachPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            BreachPayload::fromLayer,
            ByteBufCodecs.VAR_INT,
            BreachPayload::toLayer,
            ByteBufCodecs.BOOL,
            BreachPayload::fades,
            ByteBufCodecs.BOOL,
            BreachPayload::swapped,
            BreachPayload::new);

    /** Registers the payload type and sends it to every player who crosses. */
    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        BreachEvents.CROSSED.register((entity, from, to, fromLayer, toLayer) -> {
            // The event fires for the whole vehicle tree; only players have a client to tell.
            if (entity instanceof ServerPlayer player && ServerPlayNetworking.canSend(player, TYPE)) {
                ServerPlayNetworking.send(player, of(from, to, fromLayer, toLayer, SeamSwap.announcing()));
            }
        });
    }

    /**
     * The payload for a crossing that moved the player between levels with ({@code swapped}) or without a respawn: a
     * swap never fades.
     */
    public static BreachPayload of(ServerLevel from, ServerLevel to, int fromLayer, int toLayer, boolean swapped) {
        return new BreachPayload(fromLayer, toLayer, from != to && !swapped, swapped);
    }

    /** True for a descent into a deeper layer, false for an ascent. */
    public boolean descent() {
        return toLayer > fromLayer;
    }

    @Override
    public Type<BreachPayload> type() {
        return TYPE;
    }
}
