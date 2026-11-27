package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.creature.TallyTuning;

/** What the Tally tests work out from {@link TallyTuning}; production reads none of it. */
public final class TallyTimings {
    private TallyTimings() {}

    /** Ticks the cutter takes to break a clamped Tally once the pod drills: the first bite as the drill starts, then one each {@code biteTicks}. */
    public static int cutterBreakTicks(TallyTuning tuning) {
        int bites = (int) Math.ceil(tuning.maxHealth() / tuning.biteDamage());
        return (bites - 1) * tuning.biteTicks();
    }
}
