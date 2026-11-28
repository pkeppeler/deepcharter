package io.github.pkeppeler.deepcharter.transmission;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Clientbound, the answer to {@link TransmissionLogRequest} (#1615): every transmission the player has been sent, in
 * the order their charter received them, with the values of the two fields, as {@link TransmissionPayload} carries them
 * for one. Empty for a player in no charter. The client looks each one up in its own {@link TransmissionCatalog}.
 *
 * @param charter  the charter's name, for {@code [CHARTER]}; empty with no charter
 * @param director the Director's name, for {@code [DIRECTOR]}
 */
public record TransmissionLogPayload(List<Identifier> received, String charter, String director)
        implements CustomPacketPayload {
    public static final Type<TransmissionLogPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "transmission_log"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TransmissionLogPayload> CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()),
            TransmissionLogPayload::received,
            ByteBufCodecs.STRING_UTF8,
            TransmissionLogPayload::charter,
            ByteBufCodecs.STRING_UTF8,
            TransmissionLogPayload::director,
            TransmissionLogPayload::new);

    public TransmissionLogPayload {
        received = List.copyOf(received);
    }

    /** One transmission of the log as the overlay takes it, to replay it. */
    public TransmissionPayload entry(Identifier transmission) {
        return new TransmissionPayload(transmission, charter, director);
    }

    @Override
    public Type<TransmissionLogPayload> type() {
        return TYPE;
    }
}
