package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Clientbound: open this Note to read, because the player used its block in the world. The Note's text is a language
 * key, so a player on no charter can read it too; it is filed for them alone until they found or join one
 * ({@link FindResult#NO_CHARTER}). The read mark is reported through {@link HandbookReadPayload}, which the server only
 * accepts for a Note the player has found, through their charter or on their own.
 */
public record OpenNotePayload(Identifier note) implements CustomPacketPayload {
    public static final Type<OpenNotePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "open_note"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenNotePayload> CODEC =
            StreamCodec.composite(Identifier.STREAM_CODEC, OpenNotePayload::note, OpenNotePayload::new);

    /** Registers the payload type. */
    static void register() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    /**
     * What to open after a Note block was used: the Note, unless it has no text yet ({@link FindResult#UNKNOWN}) or the saved data
     * cannot be read ({@link FindResult#UNREADABLE}). Pure.
     */
    public static Optional<OpenNotePayload> after(FindResult result, Identifier note) {
        return switch (result) {
            case FOUND, ALREADY_FOUND, NO_CHARTER -> Optional.of(new OpenNotePayload(note));
            case UNKNOWN, UNREADABLE -> Optional.empty();
        };
    }

    /** Tells {@code player}, if they can receive it, to open the Note. */
    public static void send(ServerPlayer player, OpenNotePayload payload) {
        if (ServerPlayNetworking.canSend(player, TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    @Override
    public Type<OpenNotePayload> type() {
        return TYPE;
    }
}
