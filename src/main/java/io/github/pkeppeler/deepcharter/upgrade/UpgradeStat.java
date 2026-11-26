package io.github.pkeppeler.deepcharter.upgrade;

import io.github.pkeppeler.deepcharter.pod.PartAxis;
import io.github.pkeppeler.deepcharter.pod.PodBraceTuning;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodHeatShieldTuning;
import io.github.pkeppeler.deepcharter.pod.PodLinerTuning;
import io.github.pkeppeler.deepcharter.pod.PodSounderTuning;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * A figure a track's tier sets, worked out the way gameplay works it out: the one model of per-tier figures that the
 * upgrade terminal's gains (#1440) and the handbook's spec sheets both read, so the two cannot drift. A track that
 * moves a pod stat has one figure for each of its {@link PodComponents#axes} ({@link #of(PartAxis)}), read through
 * {@link PodComponents#axisAt}; any other track has one figure from its own tuning. The first figure of a track is
 * the one its spec sheet rates it by.
 *
 * <p>Each figure has a phrase ({@code screen.deepcharter.upgrade.stat.<id>}) and a unit
 * ({@code screen.deepcharter.upgrade.unit.<unit>}), so a retune changes the numbers shown and never leaves them stale.
 * The spoil hopper has no figure: it keeps spoil, and has one tier.
 */
public enum UpgradeStat {
    DRILL_SPEED(
            ComponentTrack.DRILL,
            Better.HIGHER,
            Shown.RISE,
            Unit.TIMES,
            tier -> PodStats.base().ticksPerHardness()
                    / PodComponents.axisAt(ComponentTrack.DRILL, PartAxis.DRILL_TIME, tier)),
    HULL(ComponentTrack.HULL, Better.HIGHER, Shown.CHANGE, Unit.PLAIN, axis(ComponentTrack.HULL, PartAxis.MAX_HULL)),
    LIFT(
            ComponentTrack.ENGINE,
            Better.HIGHER,
            Shown.RISE,
            Unit.PLAIN,
            axis(ComponentTrack.ENGINE, PartAxis.ENGINE_POWER)),
    SPEED(
            ComponentTrack.ENGINE,
            Better.HIGHER,
            Shown.RISE,
            Unit.PLAIN,
            axis(ComponentTrack.ENGINE, PartAxis.HORIZONTAL_SPEED)),
    TANK(
            ComponentTrack.FUEL_TANK,
            Better.HIGHER,
            Shown.CHANGE,
            Unit.LITRES,
            axis(ComponentTrack.FUEL_TANK, PartAxis.TANK_LITRES)),
    CARGO(
            ComponentTrack.CARGO_BAY,
            Better.HIGHER,
            Shown.CHANGE,
            Unit.SLOTS,
            axis(ComponentTrack.CARGO_BAY, PartAxis.CARGO_SLOTS)),
    HEAT_DAMAGE(
            ComponentTrack.RADIATOR,
            Better.LOWER,
            Shown.CHANGE,
            Unit.SHARE,
            tier -> UpgradeTuning.DEFAULT.ratio(ComponentTrack.RADIATOR, tier)),
    SCAN_DEPTH(ComponentTrack.SCANNER, Better.HIGHER, Shown.CHANGE, Unit.BLOCKS, tier ->
            (float) ScannerTuning.DEFAULT.area(tier).down()),
    LIGHT(ComponentTrack.LIGHTS, Better.HIGHER, Shown.CHANGE, Unit.PLAIN, tier ->
            (float) Math.round(UpgradeTuning.DEFAULT.value(ComponentTrack.LIGHTS, tier))),
    SIGNAL_REACH(
            ComponentTrack.CONSOLE,
            Better.LOWER,
            Shown.CHANGE,
            Unit.SHARE,
            tier -> UpgradeTuning.DEFAULT.ratio(ComponentTrack.CONSOLE, tier)),
    LINER_RING(ComponentTrack.LINER, Better.LOWER, Shown.CHANGE, Unit.SLABS, tier ->
            (float) PodLinerTuning.DEFAULT.tier(tier).ringEverySlabs()),
    SOUNDER_DEPTH(ComponentTrack.SOUNDER, Better.HIGHER, Shown.CHANGE, Unit.SLABS, tier ->
            (float) PodSounderTuning.DEFAULT.tier(tier).slabsBelow()),
    BRACE_PATCH(
            ComponentTrack.BRACE, Better.HIGHER, Shown.CHANGE, Unit.PLAIN, tier -> PodBraceTuning.DEFAULT.hullPerOre()),
    BRICK_HULL(
            ComponentTrack.HEAT_SHIELD,
            Better.HIGHER,
            Shown.CHANGE,
            Unit.PLAIN,
            tier -> PodHeatShieldTuning.DEFAULT.tier(tier).hullPerBrick());

    /** Which way a figure gets better as the tiers go up. */
    private enum Better {
        HIGHER,
        LOWER
    }

    /** How a gain reads: as a rise in percent ("LIFT +7%") or as the figure before and after ("TANK 15 -> 25 L"). */
    private enum Shown {
        RISE,
        CHANGE
    }

    /**
     * How a figure is written. A share is a fraction, written as a percent. A unit that is a word after the number
     * ({@code word}) is written once, after the figure a gain goes to: "TANK 15 -> 25 L".
     */
    private enum Unit {
        PLAIN(false),
        TIMES(false),
        PERCENT(false),
        SHARE(false),
        LITRES(true),
        SLOTS(true),
        SLABS(true),
        BLOCKS(true);

        private final boolean word;

        Unit(boolean word) {
            this.word = word;
        }

        String key() {
            return "screen.deepcharter.upgrade.unit." + name().toLowerCase(Locale.ROOT);
        }
    }

    private final ComponentTrack track;
    private final Better better;
    private final Shown shown;
    private final Unit unit;
    /** The figure at a tier with a part: never asked for tier 0 of a track with no stock part. */
    private final IntFunction<Float> value;

    UpgradeStat(ComponentTrack track, Better better, Shown shown, Unit unit, IntFunction<Float> value) {
        this.track = track;
        this.better = better;
        this.shown = shown;
        this.unit = unit;
        this.value = value;
    }

    private static IntFunction<Float> axis(ComponentTrack track, PartAxis axis) {
        return tier -> PodComponents.axisAt(track, axis, tier);
    }

    /** The figure of a pod stat a part moves. A new axis fails to compile until it has one. */
    public static UpgradeStat of(PartAxis axis) {
        return switch (axis) {
            case DRILL_TIME -> DRILL_SPEED;
            case MAX_HULL -> HULL;
            case ENGINE_POWER -> LIFT;
            case HORIZONTAL_SPEED -> SPEED;
            case TANK_LITRES -> TANK;
            case CARGO_SLOTS -> CARGO;
        };
    }

    /** The figures of {@code track}, one for each axis it moves or else its own: the first is its spec sheet's. */
    public static List<UpgradeStat> of(ComponentTrack track) {
        List<PartAxis> axes = PodComponents.axes(track);
        if (!axes.isEmpty()) {
            return axes.stream().map(UpgradeStat::of).toList();
        }
        return Arrays.stream(values()).filter(stat -> stat.track == track).toList();
    }

    /** The lang key of the figure's phrase, such as "LIFT". */
    public String phraseKey() {
        return "screen.deepcharter.upgrade.stat." + name().toLowerCase(Locale.ROOT);
    }

    /** The lang key of the figure's unit, a template with the number in it, such as "%s L". */
    public String unitKey() {
        return unit.key();
    }

    /** The figure with a part of {@code tier} fitted (0: the stock part), or empty where the track has no part. */
    public Optional<Float> value(int tier) {
        if (tier == 0 && !UpgradeTuning.DEFAULT.hasStock(track)) {
            return Optional.empty();
        }
        return Optional.of(value.apply(tier));
    }

    /**
     * The figure at {@code tier} as both the terminal and the spec sheet write it, without its unit: a share as a
     * percent, to two places at most and with no trailing ".0".
     */
    public String figure(int tier) {
        float number = value(tier).orElseThrow(() -> new IllegalArgumentException(name() + " has no part at tier 0"));
        float scaled = unit == Unit.SHARE ? number * 100f : number;
        float rounded = Math.round(scaled * 100f) / 100f;
        return rounded == Math.rint(rounded) ? Long.toString((long) rounded) : Float.toString(rounded);
    }

    /** What going from a part of {@code from} to one of {@code to} does to this figure, such as "TANK 15 -> 25 L". */
    public Component gain(int from, int to) {
        Component phrase = Component.translatable(phraseKey());
        Optional<Float> before = value(from);
        if (before.isEmpty()) {
            return Component.translatable("screen.deepcharter.upgrade.gain.new", phrase, written(to));
        }
        if (shown == Shown.RISE) {
            float after = value(to).orElseThrow();
            float rise = better == Better.HIGHER ? after / before.get() - 1f : before.get() / after - 1f;
            return Component.translatable(
                    "screen.deepcharter.upgrade.gain.rise",
                    phrase,
                    Component.translatable(Unit.PERCENT.key(), "+" + Math.round(rise * 100f)));
        }
        Object start = unit.word ? figure(from) : written(from);
        return Component.translatable("screen.deepcharter.upgrade.gain.change", phrase, start, written(to));
    }

    /** The gains of every figure of {@code track} from a part of {@code from} to one of {@code to}, in one line. */
    public static Component gains(ComponentTrack track, int from, int to) {
        MutableComponent line = Component.empty();
        List<UpgradeStat> stats = of(track);
        for (int i = 0; i < stats.size(); i++) {
            line.append(i == 0 ? "" : "  ").append(stats.get(i).gain(from, to));
        }
        return line;
    }

    private Component written(int tier) {
        return Component.translatable(unitKey(), figure(tier));
    }
}
