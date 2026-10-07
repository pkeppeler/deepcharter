package io.github.pkeppeler.deepcharter.charter;

import java.util.Optional;
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
 * Clientbound: the receiving player's charter as it is now, or empty when they are on none. The server sends the whole value
 * whenever it changes and when the player joins, so the client never has to merge.
 */
public record CharterSyncPayload(Optional<CharterView> charter) implements CustomPacketPayload {
	public static final Type<CharterSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "charter_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CharterSyncPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.optional(CharterView.STREAM_CODEC), CharterSyncPayload::charter,
			CharterSyncPayload::new);

	/** Registers the payload type. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}

	/** Tells {@code player}, if they are online, which charter they are on. */
	public static void send(MinecraftServer server, UUID player) {
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online != null) {
			send(server, online);
		}
	}

	/**
	 * Tells {@code player} which charter they are on. Use this form from the join event: the player is not yet in the
	 * server's lookup by UUID at that point, so the UUID form would find nobody.
	 */
	public static void send(MinecraftServer server, ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, TYPE)) {
			return;
		}
		UUID id = player.getUUID();
		Optional<CharterView> view = CharterData.get(server).charterOf(id).map(charter -> CharterView.of(charter, id));
		ServerPlayNetworking.send(player, new CharterSyncPayload(view));
	}

	@Override
	public Type<CharterSyncPayload> type() {
		return TYPE;
	}
}
