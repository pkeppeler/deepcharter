package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Serverbound: the navigator pressed the ping key. It carries nothing: the server finds the pod the sender rides and decides ({@link PodSonar#pingBy}). */
public record SonarPingPayload() implements CustomPacketPayload {
    public static final Type<SonarPingPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sonar_ping"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SonarPingPayload> CODEC =
            StreamCodec.unit(new SonarPingPayload());

    /** Registers the payload type and its receiver. */
    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> PodSonar.pingBy(context.player()));
    }

    @Override
    public Type<SonarPingPayload> type() {
        return TYPE;
    }
}
