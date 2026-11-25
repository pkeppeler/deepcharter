package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ByIdMap;

/**
 * Clientbound: the incident report of an outpost, sent to the first member of its charter to open one of its terminals on return. The
 * client shows it over the terminal's screen and decides nothing.
 *
 * @param outpost the outpost's layer, which is the number the game calls it by
 * @param visits  visits met while nobody was near
 * @param lines   what the visits took or broke, by item
 * @param heaps   salvage heaps a broken storage block left
 * @param causes  why the visits came, one line of the report for each
 */
public record IncidentReportPayload(int outpost, int visits, List<Line> lines, int heaps, List<VisitCause> causes)
        implements CustomPacketPayload {
    public static final Type<IncidentReportPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "incident_report"));
    /** The most lines a report holds, so it fits the screen: the server keeps the biggest, and a client never reads an unbounded list. */
    public static final int MAX_LINES = 8;

    /** What a line of the report says happened to its items. */
    public enum Kind {
        /** Pod fuel taken from storage. */
        FUEL,
        /** Ore removed from storage. */
        ORE,
        /** Items lost with a broken block. */
        BROKEN
    }

    /** One line of the report. */
    public record Line(Identifier item, int count, Kind kind) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Line> CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC,
                Line::item,
                ByteBufCodecs.VAR_INT,
                Line::count,
                ByteBufCodecs.idMapper(
                        ByIdMap.continuous(Kind::ordinal, Kind.values(), ByIdMap.OutOfBoundsStrategy.ZERO),
                        Kind::ordinal),
                Line::kind,
                Line::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, IncidentReportPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            IncidentReportPayload::outpost,
            ByteBufCodecs.VAR_INT,
            IncidentReportPayload::visits,
            Line.CODEC.apply(ByteBufCodecs.list(MAX_LINES)),
            IncidentReportPayload::lines,
            ByteBufCodecs.VAR_INT,
            IncidentReportPayload::heaps,
            VisitCause.STREAM_CODEC.apply(ByteBufCodecs.list(VisitCause.MAX)),
            IncidentReportPayload::causes,
            IncidentReportPayload::new);

    public IncidentReportPayload {
        lines = List.copyOf(lines);
        causes = List.copyOf(causes);
    }

    /** Registers the payload type. */
    static void register() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    @Override
    public Type<IncidentReportPayload> type() {
        return TYPE;
    }
}
