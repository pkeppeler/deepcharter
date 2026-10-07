package io.github.pkeppeler.deepcharter.layer;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Clientbound: the receiving player has just crossed a breach, from layer {@code fromLayer} to
 * {@code toLayer}. The client answers with a rumble, a fade and a transmission. This is the only
 * custom payload in M1.
 */
public record BreachPayload(int fromLayer, int toLayer) implements CustomPacketPayload {
	public static final Type<BreachPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "breach"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BreachPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, BreachPayload::fromLayer,
			ByteBufCodecs.VAR_INT, BreachPayload::toLayer,
			BreachPayload::new);

	/** Registers the payload type and sends it to every player who crosses. */
	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
		BreachEvents.CROSSED.register((entity, from, to, fromLayer, toLayer) -> {
			// The event fires for the whole vehicle tree; only players have a client to tell.
			if (entity instanceof ServerPlayer player && ServerPlayNetworking.canSend(player, TYPE)) {
				ServerPlayNetworking.send(player, new BreachPayload(fromLayer, toLayer));
			}
		});
	}

	/** True for a descent into a deeper layer, false for an ascent. */
	public boolean descent() {
		return toLayer > fromLayer;
	}

	@Override
	public Type<BreachPayload> type() {
		return TYPE;
	}
}
