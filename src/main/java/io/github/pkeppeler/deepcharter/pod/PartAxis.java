package io.github.pkeppeler.deepcharter.pod;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * One stat a part track moves ({@link PodComponents#axes}), and which way is better. A part scales the stat by the tier's ratio against the
 * stock part, so it composes with other listeners. A drill is better when its ticks for each hardness are fewer, so it divides.
 */
public enum PartAxis {
    DRILL_TIME(PodStats::ticksPerHardness, PodStats::withTicksPerHardness, true),
    MAX_HULL(PodStats::maxHull, PodStats::withMaxHull, false),
    ENGINE_POWER(PodStats::enginePower, PodStats::withEnginePower, false),
    HORIZONTAL_SPEED(PodStats::horizontalSpeed, PodStats::withHorizontalSpeed, false),
    TANK_LITRES(PodStats::tankLitres, PodStats::withTankLitres, false),
    CARGO_SLOTS(stats -> (float) stats.cargoSlots(), (stats, slots) -> stats.withCargoSlots(Math.round(slots)), false);

    private final Function<PodStats, Float> read;
    private final BiFunction<PodStats, Float, PodStats> write;
    private final boolean lowerIsBetter;

    PartAxis(Function<PodStats, Float> read, BiFunction<PodStats, Float, PodStats> write, boolean lowerIsBetter) {
        this.read = read;
        this.write = write;
        this.lowerIsBetter = lowerIsBetter;
    }

    /** The stat's value in {@code stats}. */
    public float read(PodStats stats) {
        return read.apply(stats);
    }

    /** True when a smaller value is the better part. */
    public boolean lowerIsBetter() {
        return lowerIsBetter;
    }

    /** {@code stats} with this stat made {@code ratio} times better. */
    PodStats scale(PodStats stats, float ratio) {
        float value = read(stats);
        return write.apply(stats, lowerIsBetter ? value / ratio : value * ratio);
    }
}
