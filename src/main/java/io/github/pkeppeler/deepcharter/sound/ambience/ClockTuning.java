package io.github.pkeppeler.deepcharter.sound.ambience;

/**
 * Tunables for the punch clock that calls the hours (#938), read as {@code ClockTuning.DEFAULT.thing()}. All times are client ticks.
 *
 * @param range           a player this close (blocks) to a Company clock may hear it; the timer only runs while one is
 * @param firstMinTicks   the first call after arriving is not sooner than this
 * @param gapMinTicks     after a call the gap is drawn between this and {@code gapMaxTicks}: the two ends average an hour of play, and the
 *                        draw is wide, so there is no period to learn
 * @param gapMaxTicks     the latest of that draw, and of the first call after arriving
 * @param retryMinTicks   a call that falls due while something else sounds tries again this soon at the earliest
 * @param retryMaxTicks   and this late at the latest
 * @param rightPercent    the share of calls that are the world's own hour
 * @param skippedPercent  the share that skip the world's hour (the hour before it, then the hour after); the rest call a wrong hour
 * @param wordTicks       the longest cut of an hour lasts this long ({@code tools/sounds/lambert_clock.py})
 * @param pauseTicks      the pause between the two hours of a skipping call, after the first has been said
 */
public record ClockTuning(
        int range,
        int firstMinTicks,
        int gapMinTicks,
        int gapMaxTicks,
        int retryMinTicks,
        int retryMaxTicks,
        int rightPercent,
        int skippedPercent,
        int wordTicks,
        int pauseTicks) {
    /** A call about once an hour near the clock: every 30 to 90 minutes, the first not before five. */
    public static final ClockTuning DEFAULT = new ClockTuning(24, 6_000, 36_000, 108_000, 400, 1_800, 40, 20, 32, 40);
}
