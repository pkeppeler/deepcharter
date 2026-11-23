package io.github.pkeppeler.deepcharter.suit;

import io.github.pkeppeler.deepcharter.outpost.Ratings;

/**
 * Tunables of the suit timer (#993, ADR 0083), read as {@code SuitTuning.DEFAULT.thing()}. The timer is a reserve of suit-seconds a
 * person on foot spends where their depth asks something, at a rate that is the depth's row measured against the suit's own ratings.
 *
 * @param capacity      the full reserve, in suit-seconds
 * @param base          what a second on foot costs wherever the depth asks something
 * @param heat          the cost of a second for each level the depth's heat is above the suit's
 * @param pressure      the cost of a second for each level the depth's pressure is above the suit's
 * @param flooding      the cost of a second for each level the depth's flooding is above the suit's, while wet
 * @param stock         what the stock suit is rated, as a block is: no track raises it yet
 * @param uncharted     what a depth with no row asks (the campaign's unbuilt depth): the SPEC's "uncharted layers" are the worst there is
 * @param leakShare     the share of the outside rate that runs inside an uninhabitable hall
 * @param refillPerSecond how many suit-seconds a second of rest gives back: a powered pod seat or a habitable hall
 * @param warnSeconds   the time left at which the amber warning starts
 * @param urgentSeconds the time left at which the red warning starts
 * @param breachDamage  the hit points one breach hit takes
 * @param breachTicks   the ticks between breach hits
 */
public record SuitTuning(
        float capacity,
        float base,
        float heat,
        float pressure,
        float flooding,
        Ratings stock,
        Ratings uncharted,
        float leakShare,
        float refillPerSecond,
        float warnSeconds,
        float urgentSeconds,
        float breachDamage,
        int breachTicks) {
    /**
     * (A) Every value. Capacity 180 s; at the unbuilt depth's (5,5,5) a stock suit lasts 33 s dry and 17 s wet, an unrated hall doubles
     * the time, a rest of 12 s fills it, and a breach kills a full-health person in 20 s.
     */
    public static final SuitTuning DEFAULT = new SuitTuning(
            180f, 0.5f, 0.5f, 0.5f, 1.0f, Ratings.NONE, new Ratings(5, 5, 5, 0), 0.5f, 15f, 30f, 10f, 2f, 40);

    public SuitTuning {
        if (capacity <= 0 || refillPerSecond <= 0 || breachTicks < 1 || breachDamage <= 0) {
            throw new IllegalArgumentException(
                    "the suit needs a capacity, a refill, a breach cadence and a breach damage");
        }
        if (leakShare <= 0 || leakShare > 1) {
            throw new IllegalArgumentException("leakShare is a share of the outside rate, got " + leakShare);
        }
        if (urgentSeconds >= warnSeconds) {
            throw new IllegalArgumentException("the red warning comes after the amber one");
        }
    }
}
