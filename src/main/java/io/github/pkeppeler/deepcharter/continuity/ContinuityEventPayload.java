package io.github.pkeppeler.deepcharter.continuity;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Clientbound, to the person who just died, before vanilla's combat-kill packet: the numbers the skull is written in.
 *
 * @param number   this Continuity Event's number, or 0 when the person's record cannot be read
 * @param employee the person's employee number ({@link ContinuityEvents#employeeNumber})
 */
public record ContinuityEventPayload(int number, int employee) implements CustomPacketPayload {
    public static final Type<ContinuityEventPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "continuity_event"));
    public static final StreamCodec<ByteBuf, ContinuityEventPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            ContinuityEventPayload::number,
            ByteBufCodecs.VAR_INT,
            ContinuityEventPayload::employee,
            ContinuityEventPayload::new);

    public ContinuityEventPayload {
        if (number < 0 || employee < ContinuityEvents.FIRST_EMPLOYEE || employee > ContinuityEvents.LAST_EMPLOYEE) {
            throw new IllegalArgumentException("no Continuity Event is No. " + number + " of employee " + employee);
        }
    }

    static void register() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    @Override
    public Type<ContinuityEventPayload> type() {
        return TYPE;
    }
}
