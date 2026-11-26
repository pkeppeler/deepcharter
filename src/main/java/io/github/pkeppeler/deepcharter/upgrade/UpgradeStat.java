package io.github.pkeppeler.deepcharter.upgrade;

import io.github.pkeppeler.deepcharter.pod.PartAxis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodHeatShieldTuning;
import io.github.pkeppeler.deepcharter.pod.PodLinerTuning;
import io.github.pkeppeler.deepcharter.pod.PodSounderTuning;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * A number a track's tier sets, as the upgrade terminal shows a tier's gain (#1440). The stats of a track that moves a
 * pod stat are its {@link PodComponents#axes}, read through {@link PodComponents#axisAt}; a track read through its tier
 * has one stat from its own tuning. Each stat has a phrase ({@code screen.deepcharter.upgrade.stat.<id>}) and a unit
 * ({@code screen.deepcharter.upgrade.unit.<unit>}), so a retune changes the numbers shown and never leaves them stale.
 *
 * <p>The spoil hopper and the brace have no stat: their one tier is crafted, so the terminal sells nothing of them.
 */
public enum UpgradeStat {
    DRILL_SPEED(ComponentTrack.DRILL, PartAxis.DRILL_TIME, Shown.RISE, Unit.PERCENT),
    HULL(ComponentTrack.HULL, PartAxis.MAX_HULL, Shown.CHANGE, Unit.PLAIN),
    LIFT(ComponentTrack.ENGINE, PartAxis.ENGINE_POWER, Shown.RISE, Unit.PERCENT),
    SPEED(ComponentTrack.ENGINE, PartAxis.HORIZONTAL_SPEED, Shown.RISE, Unit.PERCENT),
    TANK(ComponentTrack.FUEL_TANK, PartAxis.TANK_LITRES, Shown.CHANGE, Unit.LITRES),
    CARGO(ComponentTrack.CARGO_BAY, PartAxis.CARGO_SLOTS, Shown.CHANGE, Unit.SLOTS),
    HEAT_DAMAGE(
            ComponentTrack.RADIATOR,
            tier -> UpgradeTuning.DEFAULT.ratio(ComponentTrack.RADIATOR, tier),
            Shown.CHANGE,
            Unit.SHARE),
    SCAN_AREA(ComponentTrack.SCANNER, tier -> tuned(ComponentTrack.SCANNER, tier), Shown.CHANGE, Unit.SHARE),
    LIGHT(ComponentTrack.LIGHTS, tier -> tuned(ComponentTrack.LIGHTS, tier), Shown.CHANGE, Unit.PLAIN),
    SIGNAL_DECAY(ComponentTrack.CONSOLE, tier -> tuned(ComponentTrack.CONSOLE, tier), Shown.CHANGE, Unit.SHARE),
    LINER_RING(
            ComponentTrack.LINER,
            tier -> tier == 0 ? null : (float) PodLinerTuning.DEFAULT.tier(tier).ringEverySlabs(),
            Shown.CHANGE,
            Unit.SLABS),
    SOUNDER_DEPTH(
            ComponentTrack.SOUNDER,
            tier -> tier == 0
                    ? null
                    : (float) PodSounderTuning.DEFAULT.tier(tier).slabsBelow(),
            Shown.CHANGE,
            Unit.SLABS),
    BRICK_HULL(
            ComponentTrack.HEAT_SHIELD,
            tier -> tier == 0 ? null : PodHeatShieldTuning.DEFAULT.tier(tier).hullPerBrick(),
            Shown.CHANGE,
            Unit.PLAIN);

    /** How a gain reads: as a rise in percent ("LIFT +7%") or as the number before and after ("TANK 15 L -> 25 L"). */
    private enum Shown {
        RISE,
        CHANGE
    }

    /** How a number is written. A share is a fraction, written as a percent. */
    private enum Unit {
        PLAIN,
        PERCENT,
        SHARE,
        LITRES,
        SLOTS,
        SLABS;

        String key() {
            return "screen.deepcharter.upgrade.unit." + name().toLowerCase(Locale.ROOT);
        }
    }

    private final ComponentTrack track;
    private final @Nullable PartAxis axis;
    /** The stat at a tier, or null for a tier with no part (no stock part, tier 0). */
    private final IntFunction<@Nullable Float> value;

    private final Shown shown;
    private final Unit unit;

    UpgradeStat(ComponentTrack track, PartAxis axis, Shown shown, Unit unit) {
        this.track = track;
        this.axis = axis;
        this.value = tier -> PodComponents.axisAt(track, axis, tier);
        this.shown = shown;
        this.unit = unit;
    }

    UpgradeStat(ComponentTrack track, IntFunction<@Nullable Float> value, Shown shown, Unit unit) {
        this.track = track;
        this.axis = null;
        this.value = value;
        this.shown = shown;
        this.unit = unit;
    }

    // A track that moves a pod stat shows each of them once, so a new axis cannot reach the terminal without a phrase.
    static {
        for (ComponentTrack track : ComponentTrack.values()) {
            for (PartAxis axis : PodComponents.axes(track)) {
                long shown = of(track).stream()
                        .filter(stat -> stat.axis().equals(Optional.of(axis)))
                        .count();
                if (shown != 1) {
                    throw new IllegalStateException(
                            track.id() + " moves " + axis + ", which " + shown + " upgrade stats show, not one");
                }
            }
        }
    }

    private static float tuned(ComponentTrack track, int tier) {
        return UpgradeTuning.DEFAULT.value(track, tier);
    }

    /** The stats of {@code track}, in this enum's order. */
    public static List<UpgradeStat> of(ComponentTrack track) {
        return Arrays.stream(values()).filter(stat -> stat.track == track).toList();
    }

    /** The {@link PodComponents#axes axis} this stat shows, if the track moves a pod stat. */
    public Optional<PartAxis> axis() {
        return Optional.ofNullable(axis);
    }

    /** The lang key of the stat's phrase, such as "LIFT". */
    public String phraseKey() {
        return "screen.deepcharter.upgrade.stat." + name().toLowerCase(Locale.ROOT);
    }

    /** The lang key of the stat's unit, a template with the number in it, such as "%s L". */
    public String unitKey() {
        return unit.key();
    }

    /**
     * The stat with a part of {@code tier} fitted (0: the stock part), or empty where the track has no part at that
     * tier.
     */
    public Optional<Float> value(int tier) {
        return Optional.ofNullable(value.apply(tier));
    }

    /** What going from a part of {@code from} to one of {@code to} does to this stat, such as "TANK 15 L -> 25 L". */
    public Component gain(int from, int to) {
        float after = value(to).orElseThrow(() -> new IllegalArgumentException(name() + " has no value at tier " + to));
        Component phrase = Component.translatable(phraseKey());
        Optional<Float> before = value(from);
        if (before.isEmpty()) {
            return Component.translatable("screen.deepcharter.upgrade.gain.new", phrase, written(after));
        }
        if (shown == Shown.RISE) {
            boolean lower = axis().map(PartAxis::lowerIsBetter).orElse(false);
            float rise = lower ? before.get() / after - 1f : after / before.get() - 1f;
            return Component.translatable(
                    "screen.deepcharter.upgrade.gain.rise",
                    phrase,
                    Component.translatable(unitKey(), "+" + Math.round(rise * 100f)));
        }
        return Component.translatable(
                "screen.deepcharter.upgrade.gain.change", phrase, written(before.get()), written(after));
    }

    /** The gains of every stat of {@code track} from a part of {@code from} to one of {@code to}, in one line. */
    public static Component gains(ComponentTrack track, int from, int to) {
        MutableComponent line = Component.empty();
        List<UpgradeStat> stats = of(track);
        for (int i = 0; i < stats.size(); i++) {
            line.append(i == 0 ? "" : "  ").append(stats.get(i).gain(from, to));
        }
        return line;
    }

    private Component written(float number) {
        float scaled = unit == Unit.SHARE ? number * 100f : number;
        return Component.translatable(unitKey(), plain(scaled));
    }

    /** A whole number without its ".0", and anything else to one decimal. */
    private static String plain(float number) {
        long whole = Math.round(number);
        return Math.abs(number - whole) < 0.05f ? Long.toString(whole) : String.format(Locale.ROOT, "%.1f", number);
    }
}
