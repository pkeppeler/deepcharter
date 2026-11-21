package io.github.pkeppeler.deepcharter.sound.ambience;

import java.util.List;
import java.util.Optional;

/**
 * Tunables for the First Sounds ambience, read as {@code AmbienceTuning.DEFAULT.thing()}. All times are client ticks.
 *
 * @param layers          the rate of each layer, the first layer first: a layer deeper than the last entry takes the last, so a new layer is
 *                        never silent and needs no code. The surface and the colony (layer 0) have none
 * @param retryMinTicks   when a fragment is due while something else is sounding, the next try comes this soon at the earliest
 * @param retryMaxTicks   and this late at the latest
 * @param quietAfterTicks how long after a hymn, a phonograph, a transmission or another fragment ends nothing starts
 * @param quietPrefixes   the sound events that hold the ambience back, by the start of their path
 * @param hornRange       a player this close (blocks) to a Company horn hears the fragments from it
 * @param hornMinTicks    while near a horn, the gap after a fragment is drawn between this and {@code hornMaxTicks}, each at least the layer's
 *                        own, so a horn never makes a layer noisier
 * @param hornMaxTicks    the latest of that draw, and of the first fragment after arriving
 * @param hornFirstMinTicks the first fragment after arriving near a horn is not sooner than this, whatever the draw
 * @param hornWarmupTicks the crackle of the horn's warm-up lasts this long before the fragment speaks
 * @param distanceMin     a fragment from the dark comes from at least this many blocks away
 * @param distanceMax     and from at most this many
 * @param spotTries       places tried for a fragment from the dark: the best one is used
 */
public record AmbienceTuning(
        List<Rate> layers,
        int retryMinTicks,
        int retryMaxTicks,
        int quietAfterTicks,
        List<String> quietPrefixes,
        int hornRange,
        int hornMinTicks,
        int hornMaxTicks,
        int hornFirstMinTicks,
        int hornWarmupTicks,
        int distanceMin,
        int distanceMax,
        int spotTries) {
    /**
     * One layer's ambience: which tier it hears, and the gap between fragments, drawn at random between the two (the minimum is the least
     * gap, and the draw is wide, so there is no period to learn).
     */
    public record Rate(AmbienceTier tier, int minTicks, int maxTicks) {}

    /** The first layer once in 15 to 30 minutes, the Old Workings every 5 to 12, and every layer deeper every 3 to 8. */
    public static final AmbienceTuning DEFAULT = new AmbienceTuning(
            List.of(
                    new Rate(AmbienceTier.FAINT, 18_000, 36_000),
                    new Rate(AmbienceTier.WORN, 6_000, 14_400),
                    new Rate(AmbienceTier.BROKEN, 3_600, 9_600)),
            400,
            1_800,
            200,
            List.of("hymn.", "phonograph.", "transmission.", "ambience.", "song."),
            24,
            3_600,
            9_600,
            1_200,
            26,
            12,
            28,
            16);

    /** The rate of a layer, counted from the surface at 0, which has none. */
    public Optional<Rate> rateFor(int layer) {
        return layer < 1 ? Optional.empty() : Optional.of(layers.get(Math.min(layer, layers.size()) - 1));
    }
}
