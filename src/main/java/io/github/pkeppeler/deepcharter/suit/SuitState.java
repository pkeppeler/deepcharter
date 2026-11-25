package io.github.pkeppeler.deepcharter.suit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.outpost.RatingAxis;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One person's suit (#993, ADR 0083), the player attachment {@link SuitRegistry#SUIT}: the reserve of suit-seconds, whether they were told
 * why it drains, and (sent to the client alone, never saved) what the suit is doing now.
 *
 * @param reserve   suit-seconds left, 0 to the suit's capacity ({@link SuitTracks#capacity})
 * @param explained whether the first drain's line was shown: once for each player, kept across a death
 * @param mode      what the suit did in the last second; never saved, so a loaded player starts {@link Mode#INERT}
 * @param rate      the suit-seconds it costs each second as things stand, 0 when it does not drain; never saved
 * @param shortfall how far each axis is short, {@value #SHORT_BITS} bits an axis at {@code ordinal * SHORT_BITS}; sent from v2 on, never saved
 */
public record SuitState(float reserve, boolean explained, Mode mode, float rate, int shortfall) {
    /** 2: the wire carries {@code shortfall}, from which the axes that drain are derived ({@link #axes}). The saved body is the same as v1's, which still loads. */
    public static final int VERSION = 2;
    /** The bits of an axis in {@link #shortfall}, and the most a level can be short by there. */
    static final int SHORT_BITS = 4;

    static final int SHORT_MAX = (1 << SHORT_BITS) - 1;

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
            .apply(instance, (reserve, explained) -> new SuitState(sane(reserve), explained, Mode.INERT, 0f, 0)));

    /** The wire body: the saved body, then the mode, the rate and the shortfall. */
    public static final StreamCodec<ByteBuf, SuitState> STREAM = StreamCodec.composite(
            ByteBufCodecs.FLOAT,
            SuitState::reserve,
            ByteBufCodecs.BOOL,
            SuitState::explained,
            ByteBufCodecs.idMapper(index -> Mode.values()[index], Mode::ordinal),
            SuitState::mode,
            ByteBufCodecs.FLOAT,
            SuitState::rate,
            ByteBufCodecs.VAR_INT,
            SuitState::shortfall,
            SuitState::new);

    /** The disk codec: v1's body is the current one, so a v1 save loads and is written back as v2. */
    public static final Codec<Versioned<SuitState>> DISK = Versioned.codec(VERSION, BODY, Map.of(1, BODY));

    /** The axes that drain the suit, a bit for each {@code RatingAxis} ordinal: those with a shortfall. */
    public int axes() {
        int mask = 0;
        for (RatingAxis axis : RatingAxis.values()) {
            if (shortBy(axis) > 0) {
                mask |= bit(axis);
            }
        }
        return mask;
    }

    private static int shortfall(RatingAxis axis, int packed) {
        return (packed >> (axis.ordinal() * SHORT_BITS)) & SHORT_MAX;
    }

    /** How many levels {@code axis} is short, 0 to 15. */
    public int shortBy(RatingAxis axis) {
        return shortfall(axis, shortfall);
    }

    /** The bit of {@code axis} in {@link #axes}. */
    public static int bit(RatingAxis axis) {
        return 1 << axis.ordinal();
    }

    /** The axes that drain the suit, in capitals, one after another with commas between: "HEAT, PRESSURE". */
    public Component axesText() {
        return joined(", ", axis -> Component.translatable("hud.deepcharter.suit.axis." + axis.id()));
    }

    /** How short each axis is, in capitals, one after another with spaces between: "HEAT -2 PRESSURE -2". */
    public Component shortText() {
        return joined(
                " ",
                axis -> Component.translatable(
                        "hud.deepcharter.suit.short",
                        Component.translatable("hud.deepcharter.suit.axis." + axis.id()),
                        shortBy(axis)));
    }

    /** {@code text} of each short axis, in axis order, with {@code separator} between. */
    private Component joined(String separator, Function<RatingAxis, Component> text) {
        return Component.empty()
                .append(Arrays.stream(RatingAxis.values())
                        .filter(axis -> shortBy(axis) > 0)
                        .map(text)
                        .reduce((a, b) ->
                                Component.empty().append(a).append(separator).append(b))
                        .orElse(Component.empty()));
    }

    /**
     * The line the first drain shows: the axes that are short, or a line of its own where none is (a row that asks only flooding, while
     * dry, drains at the base cost alone).
     */
    public MutableComponent exceededLine() {
        return axes() == 0
                ? Component.translatable("message.deepcharter.suit.draining")
                : Component.translatable("message.deepcharter.suit.exceeded", axesText());
    }

    /**
     * {@code reserve} clamped to 0 to the largest capacity any suit has; a reserve that is not a number is a full one. The suit's own
     * capacity, which a load cannot know, clamps it further each second.
     */
    static float sane(float reserve) {
        float max = SuitTuning.DEFAULT.maxCapacity();
        return Float.isNaN(reserve) ? max : Math.clamp(reserve, 0f, max);
    }

    /** A stock suit's full reserve, nothing told yet. */
    public static SuitState full() {
        return new SuitState(SuitTracks.STOCK.capacity(SuitTuning.DEFAULT), false, Mode.INERT, 0f, 0);
    }

    /** This state with {@code reserve} in place of its own. */
    SuitState withReserve(float reserve) {
        return new SuitState(reserve, explained, mode, rate, shortfall);
    }

    /** This state with its mode {@link Mode#EXPOSED}. */
    SuitState exposed() {
        return new SuitState(reserve, explained, Mode.EXPOSED, rate, shortfall);
    }

    /** This state with the first drain's line marked shown. */
    SuitState told() {
        return new SuitState(reserve, true, mode, rate, shortfall);
    }
}
