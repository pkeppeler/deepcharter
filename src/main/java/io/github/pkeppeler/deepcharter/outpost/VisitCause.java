package io.github.pkeppeler.deepcharter.outpost;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

/**
 * Why the visits of a report came, when the player can do something about it: each cause has one line in the incident report. A family of
 * visits that has a cause of its own adds a member and its lang key {@code screen.deepcharter.incident_report.<name>}.
 */
public enum VisitCause implements StringRepresentable {
    /** The Retained came because the hall was dark. */
    DARK("dark");

    /** The most causes a report carries, so a client never reads an unbounded list. */
    public static final int MAX = 8;

    public static final Codec<VisitCause> CODEC = StringRepresentable.fromEnum(VisitCause::values);
    public static final StreamCodec<ByteBuf, VisitCause> STREAM_CODEC = ByteBufCodecs.idMapper(
            ByIdMap.continuous(VisitCause::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO), VisitCause::ordinal);

    private final String serialized;

    VisitCause(String serialized) {
        this.serialized = serialized;
    }

    @Override
    public String getSerializedName() {
        return serialized;
    }
}
