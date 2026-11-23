package io.github.pkeppeler.deepcharter.suit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.outpost.RatingAxis;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One person's suit (#993, ADR 0083), the player attachment {@link SuitRegistry#SUIT}: the reserve of suit-seconds, whether they were told
 * why it drains, and (sent to the client alone, never saved) what the suit is doing now.
 *
 * @param reserve   suit-seconds left, 0 to {@link SuitTuning#capacity()}
 * @param explained whether the first drain's line was shown: once for each player, kept across a death
 * @param mode      what the suit did in the last second; never saved, so a loaded player starts {@link Mode#INERT}
 * @param axes      the axes that drain it, a bit for each {@code RatingAxis} ordinal; never saved
 * @param rate      the suit-seconds it costs each second as things stand, 0 when it does not drain; never saved
 */
public record SuitState(float reserve, boolean explained, Mode mode, int axes, float rate) {
    public static final int VERSION = 1;

    /** What the suit is doing. The order is on the wire: add at the end only. */
    public enum Mode {
        /** The depth asks nothing here, or the person is not on foot: nothing changes. */
        INERT,
        /** On foot outside, where the depth asks something: the reserve drains. */
        EXPOSED,
        /** Inside a hall whose shell does not hold its depth: the reserve drains at a share of the outside rate. */
        LEAKING,
        /** Inside a hall whose shell holds: the reserve refills. */
        SHELTERED,
        /** Seated in a powered pod: the reserve refills. */
        POD_REFILL,
        /** Seated in a pod with no power: the reserve holds. */
        POD_HOLD,
        /** The reserve is spent and the depth still asks: the suit hurts. */
        BREACHED;

        /** True when the suit pays out reserve in this mode. */
        public boolean drains() {
            return this == EXPOSED || this == LEAKING || this == BREACHED;
        }

        /** True when the suit takes reserve back in this mode. */
        public boolean refills() {
            return this == SHELTERED || this == POD_REFILL;
        }
    }

    /**
     * The saved body: the reserve and the flag. Nothing else is kept, so a mode is never stale after a load. A reserve out of range reads
     * clamped, and one that is not a number reads as a full suit ({@link #sane}).
     */
    public static final MapCodec<SuitState> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.FLOAT.fieldOf("reserve").forGetter(SuitState::reserve),
                    Codec.BOOL.optionalFieldOf("explained", false).forGetter(SuitState::explained))
            .apply(instance, (reserve, explained) -> new SuitState(sane(reserve), explained, Mode.INERT, 0, 0f)));

    /** The wire body: the saved body, then the mode, the axes and the rate. */
    public static final StreamCodec<ByteBuf, SuitState> STREAM = StreamCodec.composite(
            ByteBufCodecs.FLOAT,
            SuitState::reserve,
            ByteBufCodecs.BOOL,
            SuitState::explained,
            ByteBufCodecs.idMapper(index -> Mode.values()[index], Mode::ordinal),
            SuitState::mode,
            ByteBufCodecs.VAR_INT,
            SuitState::axes,
            ByteBufCodecs.FLOAT,
            SuitState::rate,
            SuitState::new);

    /** The bit of {@code axis} in {@link #axes}. */
    public static int bit(RatingAxis axis) {
        return 1 << axis.ordinal();
    }

    /** The axes that drain the suit, in capitals, one after another with {@code separator} between: "HEAT PRESSURE". */
    public Component axesText(String separator) {
        return Component.empty()
                .append(Arrays.stream(RatingAxis.values())
                        .filter(axis -> (axes & bit(axis)) != 0)
                        .map(axis -> (Component) Component.translatable("hud.deepcharter.suit.axis." + axis.id()))
                        .reduce((a, b) ->
                                Component.empty().append(a).append(separator).append(b))
                        .orElse(Component.empty()));
    }

    /**
     * The line the first drain shows: the axes that are short, or a line of its own where none is (a row that asks only flooding, while
     * dry, drains at the base cost alone).
     */
    public MutableComponent exceededLine() {
        return axes == 0
                ? Component.translatable("message.deepcharter.suit.draining")
                : Component.translatable("message.deepcharter.suit.exceeded", axesText(", "));
    }

    /** {@code reserve} clamped to 0 to the suit's capacity; a reserve that is not a number is a full suit. */
    static float sane(float reserve) {
        return Float.isNaN(reserve)
                ? SuitTuning.DEFAULT.capacity()
                : Math.clamp(reserve, 0f, SuitTuning.DEFAULT.capacity());
    }

    /** A full reserve, nothing told yet. */
    public static SuitState full() {
        return new SuitState(SuitTuning.DEFAULT.capacity(), false, Mode.INERT, 0, 0f);
    }

    /** This state with {@code reserve} clamped to the suit's capacity. */
    SuitState withReserve(float reserve) {
        return new SuitState(sane(reserve), explained, mode, axes, rate);
    }

    /** This state doing {@code mode} at {@code rate} on {@code axes}. */
    SuitState doing(Mode mode, int axes, float rate) {
        return new SuitState(reserve, explained, mode, axes, rate);
    }

    /** This state with the first drain's line marked shown. */
    SuitState told() {
        return new SuitState(reserve, true, mode, axes, rate);
    }
}
