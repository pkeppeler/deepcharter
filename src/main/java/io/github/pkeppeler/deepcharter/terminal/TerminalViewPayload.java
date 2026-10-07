package io.github.pkeppeler.deepcharter.terminal;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Clientbound: the state of one terminal. The client opens its screen for it, or updates the open screen of the same
 * terminal. The server sends it when it opens a terminal for a player and after each of that player's actions.
 */
public record TerminalViewPayload(TerminalView view) implements CustomPacketPayload {
	public static final Type<TerminalViewPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "terminal_view"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TerminalViewPayload> CODEC = StreamCodec.composite(
			TerminalView.STREAM_CODEC, TerminalViewPayload::view,
			TerminalViewPayload::new);

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}

	@Override
	public Type<TerminalViewPayload> type() {
		return TYPE;
	}
}
