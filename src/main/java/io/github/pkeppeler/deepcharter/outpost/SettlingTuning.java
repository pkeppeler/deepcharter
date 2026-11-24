package io.github.pkeppeler.deepcharter.outpost;

import java.util.List;

/**
 * Tunables of away settling, read as {@code SettlingTuning.DEFAULT.thing()} (ADR 0079). Visits are rolled in fixed windows of game time and
 * applied when the outpost's zone is loaded.
 *
 * @param windowTicks          game ticks in one rolling window: every window rolls each family of visit once
 * @param sweepIntervalTicks   server ticks between two sweeps over the outposts
 * @param nearBlocks           a player this near an outpost's middle (not a spectator) holds its visits: that time is never rolled
 * @param reachOut             blocks beyond the hall's shell that a visit reaches sideways
 * @param reachUp              blocks beyond the hall's ceiling that a visit reaches
 * @param reachDown            blocks beneath the hall's floor that a visit reaches
 * @param maxWindowsPerSweep   windows one sweep rolls at most for one outpost, so a long absence is caught up over several sweeps
 * @param pendingCap           visits of one family kept pending at most: an outpost that stays unloaded loses no more than this
 * @param fuelShare            the share of the stored pod fuel items that one Retained visit takes (rounded up)
 * @param maxFuelItemsPerVisit most fuel items one visit takes
 * @param maxOrePerVisit       most ore items one Recovery visit takes
 * @param maxBlocksPerVisit    most blocks one Sleeper's visit breaks
 * @param layers               the visit rates of each built layer, layer 1 first
 */
public record SettlingTuning(
        int windowTicks,
        int sweepIntervalTicks,
        int nearBlocks,
        int reachOut,
        int reachUp,
        int reachDown,
        int maxWindowsPerSweep,
        int pendingCap,
        double fuelShare,
        int maxFuelItemsPerVisit,
        int maxOrePerVisit,
        int maxBlocksPerVisit,
        List<LayerRates> layers) {
    /** Game ticks in a day: a rate "per day" is per this many ticks of game time, whatever the day clock says. */
    public static final int TICKS_PER_DAY = 24000;

    /**
     * How often each family of visit comes to an outpost of one layer, per day of game time. The Retained come to a dark outpost with stored
     * fuel, the Sleeper's to a noisy one with blocks it may break, the Recovery to one that stores ore.
     */
    public record LayerRates(double retainedPerDay, double sleepersPerDay, double recoveryPerDay) {
        /** A layer nobody has set a rate for: nothing comes. */
        public static final LayerRates NONE = new LayerRates(0, 0, 0);

        public LayerRates {
            if (retainedPerDay < 0 || sleepersPerDay < 0 || recoveryPerDay < 0) {
                throw new IllegalArgumentException("a visit rate cannot be negative: " + this);
            }
        }
    }

    /**
     * Layers 1 and 2 have a Retained rate only: the Sleeper's and the Recovery come with the creatures of #1005 and #1006, so their rates stay 0
     * until those set them.
     */
    public static final SettlingTuning DEFAULT = new SettlingTuning(
            1200,
            100,
            48,
            8,
            8,
            2,
            2000,
            10000,
            0.25,
            16,
            4,
            3,
            List.of(new LayerRates(2.0, 0, 0), new LayerRates(6.0, 0, 0)));

    public SettlingTuning {
        if (windowTicks < 1 || sweepIntervalTicks < 1 || nearBlocks < 1) {
            throw new IllegalArgumentException("a window, a sweep and the near distance each need at least 1: " + this);
        }
        if (reachOut < 0 || reachUp < 0 || reachDown < 0) {
            throw new IllegalArgumentException("a reach cannot be negative: " + this);
        }
        if (maxWindowsPerSweep < 1 || pendingCap < 1) {
            throw new IllegalArgumentException(
                    "a sweep rolls at least one window and keeps at least one visit: " + this);
        }
        if (fuelShare <= 0 || fuelShare > 1) {
            throw new IllegalArgumentException("the fuel share is above 0 and at most 1, got " + fuelShare);
        }
        if (maxFuelItemsPerVisit < 1 || maxOrePerVisit < 1 || maxBlocksPerVisit < 1) {
            throw new IllegalArgumentException("a visit takes at least one of each thing it takes: " + this);
        }
        layers = List.copyOf(layers);
    }

    /** The rates of {@code layer} (1 is the first below the surface): none for a layer with no entry. */
    public LayerRates ratesOf(int layer) {
        return layer >= 1 && layer <= layers.size() ? layers.get(layer - 1) : LayerRates.NONE;
    }

    /** The probability that one window brings one visit, at {@code perDay} visits a day, never above 1. */
    public double chancePerWindow(double perDay) {
        return Math.min(1.0, perDay * windowTicks / TICKS_PER_DAY);
    }
}
