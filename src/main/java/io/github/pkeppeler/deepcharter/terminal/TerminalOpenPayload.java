package io.github.pkeppeler.deepcharter.terminal;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Serverbound: the player asks to open the terminal at {@code pos}. The server checks it ({@link Terminals#open}) and, if it
 * allows it, answers with a {@link TerminalViewPayload}. Nothing in the client is trusted, so this is a request and never an order.
 */
public record TerminalOpenPayload(BlockPos pos) implements CustomPacketPayload {
	public static final Type<TerminalOpenPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "terminal_open"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TerminalOpenPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, TerminalOpenPayload::pos,
			TerminalOpenPayload::new);

	static void register() {
		PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> Terminals.open(context.player(), payload.pos()));
	}

	@Override
	public Type<TerminalOpenPayload> type() {
		return TYPE;
	}
}
