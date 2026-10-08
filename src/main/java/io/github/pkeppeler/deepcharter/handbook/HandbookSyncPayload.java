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
 * Clientbound: the directives the receiving player's charter has completed, as they are now (empty when they are on no charter).
 * The server sends the whole set whenever it changes and when the player joins, so the client never has to merge.
 */
public record HandbookSyncPayload(List<Identifier> completed) implements CustomPacketPayload {
	public static final Type<HandbookSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, HandbookSyncPayload> CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), HandbookSyncPayload::completed,
			HandbookSyncPayload::new);

	public HandbookSyncPayload {
		completed = List.copyOf(completed);
	}

	/** Registers the payload type. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}

	/** Tells {@code player}, if they are online, which directives their charter has completed. */
	public static void send(MinecraftServer server, UUID player) {
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online != null) {
			send(server, online);
		}
	}

	/** Use this form from the join event: the player is not yet in the server's lookup by UUID at that point. */
	public static void send(MinecraftServer server, ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, TYPE)) {
			return;
		}
		List<Identifier> completed = HandbookProgress.completedFor(server, player.getUUID()).stream().sorted().toList();
		ServerPlayNetworking.send(player, new HandbookSyncPayload(completed));
	}

	@Override
	public Type<HandbookSyncPayload> type() {
		return TYPE;
	}
}
