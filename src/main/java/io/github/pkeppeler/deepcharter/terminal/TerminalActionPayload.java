package io.github.pkeppeler.deepcharter.terminal;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Serverbound: the player presses a button of the terminal at {@code pos}. The server checks it ({@link Terminals#act}) before
 * it does anything, and tells the player the result.
 *
 * @param args what the action needs, such as {@code {part: "deepcharter:pump_motor"}}. At most {@link #MAX_ARGS_BYTES} bytes.
 */
public record TerminalActionPayload(BlockPos pos, Identifier action, CompoundTag args) implements CustomPacketPayload {
	public static final int MAX_ARGS_BYTES = 2048;
	public static final Type<TerminalActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "terminal_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TerminalActionPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, TerminalActionPayload::pos,
			Identifier.STREAM_CODEC, TerminalActionPayload::action,
			ByteBufCodecs.compoundTagCodec(() -> NbtAccounter.create(MAX_ARGS_BYTES)), TerminalActionPayload::args,
			TerminalActionPayload::new);

	static void register() {
		PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TYPE,
				(payload, context) -> Terminals.act(context.player(), payload.pos(), payload.action(), payload.args()));
	}

	@Override
	public Type<TerminalActionPayload> type() {
		return TYPE;
	}
}
