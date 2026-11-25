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

    /**
     * How far each axis is short, four bits an axis at {@code axis.ordinal() * 4} (a level is at most 15): the wire's picture of why the
     * suit drains. Flooding counts only while {@code wet}, as it costs only then.
     */
    public static int shortfall(Ratings depth, Ratings suit, boolean wet) {
        int packed = 0;
        for (RatingAxis axis : OutpostHabitability.AXES) {
            if (wet || axis != RatingAxis.FLOODING) {
                packed |= Math.min(shortBy(depth, suit, axis), SuitState.SHORT_MAX)
                        << (axis.ordinal() * SuitState.SHORT_BITS);
            }
        }
        return packed;
    }

    private static int shortBy(Ratings depth, Ratings suit, RatingAxis axis) {
        return Math.max(0, depth.of(axis) - suit.of(axis));
    }
}
