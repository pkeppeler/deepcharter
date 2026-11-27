package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Serverbound: the navigator marked the echo at {@code pos} (which the client picked by where it looks), or took its mark off. The server finds the
 * pod the sender rides, checks the seat and that the echo is on the pod's picture, and decides ({@link PodSonar#markBy}).
 */
public record SonarMarkPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<SonarMarkPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sonar_mark"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SonarMarkPayload> CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, SonarMarkPayload::pos, SonarMarkPayload::new);

    /** Registers the payload type and its receiver. */
    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(
                TYPE, (payload, context) -> PodSonar.markBy(context.player(), payload.pos()));
    }

    @Override
    public Type<SonarMarkPayload> type() {
        return TYPE;
    }
}
