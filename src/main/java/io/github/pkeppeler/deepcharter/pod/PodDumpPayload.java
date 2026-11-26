package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Serverbound: a player held the dump key long enough. It carries nothing: the server dumps the whole hold of the pod
 * the sender sits in, by {@link PodDump#dumpBay}'s rules, and tells them what it did or why not. A sender in no pod is
 * ignored.
 */
public record PodDumpPayload() implements CustomPacketPayload {
    public static final Type<PodDumpPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_dump"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PodDumpPayload> CODEC =
            StreamCodec.unit(new PodDumpPayload());

    /** Registers the payload type and its receiver. */
    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(
                TYPE,
                (payload, context) ->
                        PodDump.dumpBay(context.player()).ifPresent(context.player()::sendOverlayMessage));
    }

    @Override
    public Type<PodDumpPayload> type() {
        return TYPE;
    }
}
