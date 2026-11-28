package io.github.pkeppeler.deepcharter.transmission;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Serverbound: the player pressed the comms log key (#1615). It carries nothing: the server answers with the sender's own log
 * ({@link Transmissions#log}), so a client can never ask for another charter's.
 */
public record TransmissionLogRequest() implements CustomPacketPayload {
    public static final Type<TransmissionLogRequest> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "transmission_log_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TransmissionLogRequest> CODEC =
            StreamCodec.unit(new TransmissionLogRequest());

    /** Registers this payload and its answer, and the receiver that sends the answer. */
    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TransmissionLogPayload.TYPE, TransmissionLogPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> {
            if (ServerPlayNetworking.canSend(context.player(), TransmissionLogPayload.TYPE)) {
                ServerPlayNetworking.send(context.player(), Transmissions.log(context.server(), context.player()));
            }
        });
    }

    @Override
    public Type<TransmissionLogRequest> type() {
        return TYPE;
    }
}
