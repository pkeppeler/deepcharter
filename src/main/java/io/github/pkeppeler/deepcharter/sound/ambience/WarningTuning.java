package io.github.pkeppeler.deepcharter.sound.ambience;

/**
 * Tunables for the warning in the dark (#861), read as {@code WarningTuning.DEFAULT.thing()}. All times are client ticks.
 *
 * @param minRange        a Retained nearer than this (blocks) is too close to be a warning: the player is meeting it
 * @param maxRange        and one farther than this is not near yet
 * @param forgetRange     a figure farther than this is forgotten, so one that wanders at the edge of the range is not cued twice
 * @param cooldownTicks   after a cue, the next is not sooner than this, for any figure: several minutes, so it stays rare
 * @param confirmTicks    a figure must have been in range and unseen this long, without a break, before it is cued
 * @param viewHalfAngle   a figure within this many degrees of where the player looks, with nothing solid between, is seen
 * @param wreckQuietTicks how long after a wreck's phonograph last sounded no warning is given: the tune is learnt where it is safe
 */
public record WarningTuning(
        int minRange,
        int maxRange,
        int forgetRange,
        int cooldownTicks,
        int confirmTicks,
        double viewHalfAngle,
        int wreckQuietTicks) {
    /** A cue at most once in seven and a half minutes, 16 to 32 blocks off, after two seconds unseen. */
    public static final WarningTuning DEFAULT = new WarningTuning(16, 32, 40, 9_000, 40, 70.0, 1_200);
}
