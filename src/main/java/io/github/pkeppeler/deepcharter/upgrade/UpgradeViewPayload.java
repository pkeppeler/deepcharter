package io.github.pkeppeler.deepcharter.upgrade;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/** Clientbound: the pod parked at an upgrade terminal. The server sends it when the player asks for it and after each purchase. */
public record UpgradeViewPayload(UpgradeView view) implements CustomPacketPayload {
	public static final Type<UpgradeViewPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "upgrade_view"));
	public static final StreamCodec<RegistryFriendlyByteBuf, UpgradeViewPayload> CODEC = StreamCodec.composite(
			UpgradeView.STREAM_CODEC, UpgradeViewPayload::view,
			UpgradeViewPayload::new);

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}

	@Override
	public Type<UpgradeViewPayload> type() {
		return TYPE;
	}
}
