package io.github.pkeppeler.deepcharter.suit;

import io.github.pkeppeler.deepcharter.outpost.OutpostHabitability;
import io.github.pkeppeler.deepcharter.outpost.RatingAxis;
import io.github.pkeppeler.deepcharter.outpost.Ratings;

/**
 * What a second on foot costs the suit (#993, ADR 0083): the depth's row measured against the suit's own ratings, as a block is. Pure, so a
 * test and the pacing table can ask it without a world. The costs are summed and not maxed, so a step of any suit track shows in the time it
 * buys; quake is not a thing a suit holds against and is ignored.
 */
public final class SuitDrain {
    private SuitDrain() {}

    /** Whether the depth asks something of a suit on {@link OutpostHabitability#AXES}. Where it does not, the suit is inert. */
    public static boolean asks(Ratings depth) {
        return OutpostHabitability.AXES.stream().anyMatch(axis -> depth.of(axis) > 0);
    }

    /**
     * The suit-seconds a second costs a person in {@code depth}'s row wearing a suit rated {@code suit}: 0 where the depth asks nothing, else
     * the base cost plus a cost for each level a rating is short. Flooding costs only while {@code wet}.
     */
    public static float perSecond(Ratings depth, Ratings suit, boolean wet, SuitTuning tuning) {
        if (!asks(depth)) {
            return 0f;
        }
        float rate = tuning.base()
                + tuning.heat() * shortBy(depth, suit, RatingAxis.HEAT)
                + tuning.pressure() * shortBy(depth, suit, RatingAxis.PRESSURE);
        return wet ? rate + tuning.flooding() * shortBy(depth, suit, RatingAxis.FLOODING) : rate;
    }

    /** The axes that cost something, a bit for each {@link RatingAxis} ordinal: the bits {@link SuitState#axes} carries. */
    public static int axes(Ratings depth, Ratings suit, boolean wet) {
        int mask = 0;
        for (RatingAxis axis : OutpostHabitability.AXES) {
            if (shortBy(depth, suit, axis) > 0 && (wet || axis != RatingAxis.FLOODING)) {
                mask |= SuitState.bit(axis);
            }
        }
        return mask;
    }

    private static int shortBy(Ratings depth, Ratings suit, RatingAxis axis) {
        return Math.max(0, depth.of(axis) - suit.of(axis));
    }
}
