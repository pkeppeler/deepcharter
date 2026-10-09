package io.github.pkeppeler.deepcharter.pod;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Serverbound: the pilot pressed the lining key. It carries nothing: the server finds the pod the sender pilots and decides
 * ({@link PodLining#toggle}). A sender who is not a pod's pilot is ignored.
 */
public record LiningPayload() implements CustomPacketPayload {
	public static final Type<LiningPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_lining"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LiningPayload> CODEC = StreamCodec.unit(new LiningPayload());

	/** Registers the payload type and its receiver. */
	static void register() {
		PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> PodLining.toggle(context.player()));
	}

	@Override
	public Type<LiningPayload> type() {
		return TYPE;
	}
}
