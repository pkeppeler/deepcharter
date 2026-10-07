package io.github.pkeppeler.deepcharter.charter.terminal;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Clientbound: the receiving player's {@link ContractState}. The server sends the whole value when the player opens the
 * terminal and whenever it changes, so the open screen never has to merge. A client with no contract screen open ignores it.
 */
public record ContractStatePayload(ContractState state) implements CustomPacketPayload {
	public static final Type<ContractStatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "contract_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ContractStatePayload> CODEC = StreamCodec.composite(
			ContractState.STREAM_CODEC, ContractStatePayload::state,
			ContractStatePayload::new);

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}

	@Override
	public Type<ContractStatePayload> type() {
		return TYPE;
	}
}
