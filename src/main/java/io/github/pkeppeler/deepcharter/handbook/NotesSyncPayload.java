package io.github.pkeppeler.deepcharter.handbook;

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

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Clientbound: the Notes the receiving player's charter has found, in Note order (empty when they are on no charter). The server
 * sends the whole list whenever it changes and when the player joins, so the client never has to merge. Which of them the player
 * has read travels in their read marks ({@link ReadMarks}), not here.
 */
public record NotesSyncPayload(List<Identifier> found) implements CustomPacketPayload {
	public static final Type<NotesSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "notes_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, NotesSyncPayload> CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), NotesSyncPayload::found,
			NotesSyncPayload::new);

	public NotesSyncPayload {
		found = List.copyOf(found);
	}

	/** Registers the payload type. */
	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}

	/** The payload {@code player} is sent: empty while the saved charters or Notes are unreadable. */
	public static NotesSyncPayload of(MinecraftServer server, UUID player) {
		return new NotesSyncPayload(Notes.foundFor(server, player));
	}

	/** Tells {@code player}, if they can receive it, which Notes their charter has found. Use this form from the join event. */
	public static void send(MinecraftServer server, ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, TYPE)) {
			ServerPlayNetworking.send(player, of(server, player.getUUID()));
		}
	}

	@Override
	public Type<NotesSyncPayload> type() {
		return TYPE;
	}
}
