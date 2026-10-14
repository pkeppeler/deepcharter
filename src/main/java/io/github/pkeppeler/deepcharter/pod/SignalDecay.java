package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import java.util.OptionalInt;
import net.minecraft.world.level.Level;

/**
 * The signal decay (#502, docs/design/mechanics.md): near the floor of a layer something on the line starts to take the cab's instruments. How hard is the
 * <em>intensity</em>, 0 to 1, a pure function of the layer, the pod's slabs over the floor and its console part, so it is worked out the same on the client that draws
 * the instruments and in a test; nothing is sent. Whether an instrument fails in a given cell of time is another pure function of that intensity, the pod's seed and
 * the cell, so a pod's glitches are the same for every pilot who sits it and the same on every run of a test.
 *
 * <p>The decay only hides information. It never changes what the pod does: the drill, the brace, the liner and the hull behave as they do above it.
 */
public final class SignalDecay {
    /** The parts of the cab that can fail, in the order {@link SignalDecayTuning#thresholds} lists them failing. */
    public enum Instrument {
        SCANNER,
        HEAT,
        DEPTH,
        CARGO,
        FUEL,
        PLATE,
        LAMPS,
        HULL
    }

    /** What an instrument does in one cell of time: reads true, reads a false value, or is dark. */
    public enum Fault {
        NONE,
        JUMP,
        DROP
    }

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private SignalDecay() {}

    /**
     * The decay in {@code layer} for a pod {@code slabsAboveFloor} over its floor with a console of {@code consoleTier}: 0 from the onset up, 1 in the blackout band at the
     * floor, rising in a straight line between. The console shrinks both distances by its tier's share ({@link UpgradeTuning}): a tier with a share of 0 is immune.
     */
    public static double intensity(int layer, double slabsAboveFloor, int consoleTier) {
        float share = UpgradeTuning.DEFAULT.ratio(ComponentTrack.CONSOLE, consoleTier);
        if (share <= 0f) {
            return 0;
        }
        SignalDecayTuning.Band band = SignalDecayTuning.DEFAULT.band(layer);
        double onset = band.onset() * share;
        double blackout = band.blackout() * share;
        if (slabsAboveFloor >= onset) {
            return 0;
        }
        if (slabsAboveFloor <= blackout) {
            return 1;
        }
        return (onset - slabsAboveFloor) / (onset - blackout);
    }

    /** The decay at the pod now: 0 outside the layers, else the curve of its layer at its height with its console. Works on both sides, from synced state. */
    public static double intensity(PodEntity pod) {
        Level level = pod.level();
        OptionalInt layer = LayerChain.layerOf(
                level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier());
        if (layer.isEmpty()) {
            return 0;
        }
        return intensity(
                layer.getAsInt(),
                PodFootprint.of(pod).feetY() - level.getMinY(),
                PodComponents.effectiveTier(pod, ComponentTrack.CONSOLE));
    }

    /**
     * What {@code instrument} does in time cell {@code cell} at {@code intensity}, for the pod with {@code seed}. Dark in every cell of the blackout (intensity 1); clean
     * at and under its threshold; between, faulty in a share of cells that grows from 0 at the threshold to {@link SignalDecayTuning#maxFaultChance} just under 1.
     */
    public static Fault fault(Instrument instrument, double intensity, long seed, long cell) {
        if (intensity >= 1) {
            return Fault.DROP;
        }
        double threshold = SignalDecayTuning.DEFAULT.thresholds().get(instrument);
        if (intensity <= threshold) {
            return Fault.NONE;
        }
        double chance = (intensity - threshold) / (1 - threshold) * SignalDecayTuning.DEFAULT.maxFaultChance();
        if (unit(instrument, seed, cell, 0) >= chance) {
            return Fault.NONE;
        }
        return unit(instrument, seed, cell, 1) < SignalDecayTuning.DEFAULT.jumpShare() ? Fault.JUMP : Fault.DROP;
    }

    /** The false reading, 0 (up to but not including) 1, that a {@link Fault#JUMP} of {@code instrument} shows in {@code cell}. */
    public static double jump(Instrument instrument, long seed, long cell) {
        return unit(instrument, seed, cell, 2);
    }

    /** A number in [0, 1) that depends on all four arguments and nothing else. */
    private static double unit(Instrument instrument, long seed, long cell, int salt) {
        long z = seed + GOLDEN * (1 + instrument.ordinal() + 16L * salt) + cell * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }
}
