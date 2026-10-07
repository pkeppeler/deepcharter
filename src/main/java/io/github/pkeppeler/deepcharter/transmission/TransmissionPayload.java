package io.github.pkeppeler.deepcharter.transmission;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Clientbound: one transmission for the receiving player, with the values of its two fields. The client looks the transmission up in
 * its own {@link TransmissionCatalog}, translates it, and replaces {@code [CHARTER]} and {@code [DIRECTOR]} in the text.
 *
 * @param charter  the charter's name, for {@code [CHARTER]}
 * @param director the Director's name, for {@code [DIRECTOR]}
 */
public record TransmissionPayload(Identifier transmission, String charter, String director) implements CustomPacketPayload {
	public static final Type<TransmissionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "transmission"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TransmissionPayload> CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, TransmissionPayload::transmission,
			ByteBufCodecs.STRING_UTF8, TransmissionPayload::charter,
			ByteBufCodecs.STRING_UTF8, TransmissionPayload::director,
			TransmissionPayload::new);

	/** Registers the payload type. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}

	@Override
	public Type<TransmissionPayload> type() {
		return TYPE;
	}
}
