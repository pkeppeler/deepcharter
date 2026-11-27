package io.github.pkeppeler.deepcharter.suit;

import io.github.pkeppeler.deepcharter.outpost.Ratings;
import java.util.List;

/**
 * Tunables of the suit timer (#993, ADR 0083), read as {@code SuitTuning.DEFAULT.thing()}. The timer is a reserve of suit-seconds a
 * person on foot spends where their depth asks something, at a rate that is the depth's row measured against the suit's own ratings.
 *
 * @param capacities    the full reserve at each air tier, in suit-seconds, rising: index 0 is the stock suit
 * @param base          what a second on foot costs wherever the depth asks something
 * @param heat          the cost of a second for each level the depth's heat is above the suit's
 * @param pressure      the cost of a second for each level the depth's pressure is above the suit's
 * @param flooding      the cost of a second for each level the depth's flooding is above the suit's, while wet
 * @param uncharted     what a depth with no row asks (the campaign's unbuilt depth): the SPEC's "uncharted layers" are the worst there is
 * @param leakShare     the share of the outside rate that runs inside an uninhabitable hall
 * @param refillPerSecond how many suit-seconds a second of rest gives back: a powered pod seat or a habitable hall
 * @param warnSeconds   the time left at which the amber warning starts
 * @param urgentSeconds the time left at which the red warning starts
 * @param breachDamage  the hit points one breach hit takes
 * @param breachTicks   the ticks between breach hits
 * @param canisterSeconds the suit-seconds an air canister adds to the reserve (#1194), never past the suit's own capacity
 */
public record SuitTuning(
        List<Float> capacities,
        float base,
        float heat,
        float pressure,
        float flooding,
        Ratings uncharted,
        float leakShare,
        float refillPerSecond,
        float warnSeconds,
        float urgentSeconds,
        float breachDamage,
        int breachTicks,
        float canisterSeconds) {
    /**
     * (A) Every value. Capacities 180/240/300/360 s by air tier; at the unbuilt depth's (5,5,5) a stock suit lasts 33 s dry and 18 s wet, an unrated hall doubles
     * the time, a rest of 12 s fills it, and a breach kills a full-health person in about 20 s. A canister adds a minute.
     */
    public static final SuitTuning DEFAULT = new SuitTuning(
            List.of(180f, 240f, 300f, 360f),
            0.5f,
            0.5f,
            0.5f,
            1.0f,
            new Ratings(5, 5, 5, 0),
            0.5f,
            15f,
            30f,
            10f,
            2f,
            40,
            60f);

    public SuitTuning {
        capacities = List.copyOf(capacities);
        if (capacities.size() != SuitTrack.AIR.maxTier() + 1 || capacities.get(0) <= 0) {
            throw new IllegalArgumentException("the suit needs a capacity for each air tier, from stock up");
        }
        for (int tier = 1; tier < capacities.size(); tier++) {
            if (capacities.get(tier) <= capacities.get(tier - 1)) {
                throw new IllegalArgumentException("capacity must rise with the air tier, got " + capacities);
            }
        }
        if (refillPerSecond <= 0 || breachTicks < 1 || breachDamage <= 0) {
            throw new IllegalArgumentException("the suit needs a refill, a breach cadence and a breach damage");
        }
        if (canisterSeconds <= 0) {
            throw new IllegalArgumentException("an air canister must add some reserve, got " + canisterSeconds);
        }
        if (leakShare <= 0 || leakShare > 1) {
            throw new IllegalArgumentException("leakShare is a share of the outside rate, got " + leakShare);
        }
        if (urgentSeconds >= warnSeconds) {
            throw new IllegalArgumentException("the red warning comes after the amber one");
        }
    }

    /** The largest reserve any suit holds: the top air tier's capacity. */
    public float maxCapacity() {
        return capacities.getLast();
    }
}
