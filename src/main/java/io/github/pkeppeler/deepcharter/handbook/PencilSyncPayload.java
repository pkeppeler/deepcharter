package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Clientbound: the pencil paths the receiving player's charter has completed (their own on no charter). The server sends the whole
 * list whenever it changes and when the player joins. Which paths are drawn follows from the Notes ({@link NotesSyncPayload}).
 */
public record PencilSyncPayload(List<Identifier> completed) implements CustomPacketPayload {
    public static final Type<PencilSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pencil_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PencilSyncPayload> CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), PencilSyncPayload::completed, PencilSyncPayload::new);

    public PencilSyncPayload {
        completed = List.copyOf(completed);
    }

    /** Registers the payload type. */
    static void register() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    /** The payload {@code player} is sent: empty while the saved charters or paths are unreadable. */
    public static PencilSyncPayload of(MinecraftServer server, UUID player) {
        return new PencilSyncPayload(
                PencilPaths.completedFor(server, player).stream().sorted().toList());
    }

    /** Tells {@code player}, if they can receive it, which paths their charter has completed. Use this form from the join event. */
    static void send(MinecraftServer server, ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, TYPE)) {
            ServerPlayNetworking.send(player, of(server, player.getUUID()));
        }
    }

    @Override
    public Type<PencilSyncPayload> type() {
        return TYPE;
    }
}
