package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Serverbound: a player pressed the pod screen key. It carries nothing: the server opens the screen of the pod the
 * sender sits in, by {@link PodMenu#open}'s rules, and tells them when it refuses. A sender in no pod is ignored.
 */
public record PodScreenPayload() implements CustomPacketPayload {
    public static final Type<PodScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PodScreenPayload> CODEC =
            StreamCodec.unit(new PodScreenPayload());

    /** Registers the payload type and its receiver. */
    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> {
            if (context.player().getVehicle() instanceof PodEntity pod) {
                PodMenu.open(context.player(), pod).ifPresent(context.player()::sendOverlayMessage);
            }
        });
    }

    @Override
    public Type<PodScreenPayload> type() {
        return TYPE;
    }
}
