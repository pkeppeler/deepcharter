package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * What a sonar echo claims to be (SPEC section 8). It is the navigator's reading, not the truth: a gas pocket far off reads as an empty
 * cave, and a still thing reads as ore. A creature's signature (#924) picks one of these, so a new creature adds no kind.
 */
public enum SonarKind implements StringRepresentable {
    /** A dense return: ore, or something standing still. */
    ORE("ore"),
    /** A pocket of gas, resolved only near the pod. */
    GAS("gas"),
    /** Open space. Beyond {@link SonarTuning#gasResolveBlocks()} a gas pocket reads as this too. */
    CAVE("cave"),
    /** Something that moved. */
    MOVED("moved");

    public static final Codec<SonarKind> CODEC = StringRepresentable.fromEnum(SonarKind::values);
    public static final StreamCodec<ByteBuf, SonarKind> STREAM =
            ByteBufCodecs.idMapper(i -> values()[i], SonarKind::ordinal);

    private final String id;

    SonarKind(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    /** Lower sorts first when a ping has more echoes than the display holds: what moves, then ore, gas, and open space last. */
    int rank() {
        return switch (this) {
            case MOVED -> 0;
            case ORE -> 1;
            case GAS -> 2;
            case CAVE -> 3;
        };
    }
}
