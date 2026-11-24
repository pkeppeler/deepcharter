package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Pending;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Survey;
import io.github.pkeppeler.deepcharter.outpost.SettlingTuning.LayerRates;
import net.minecraft.util.RandomSource;

/**
 * Rolls the visits an outpost met over a run of windows, from nothing but its seed, the windows and the draws its last survey found
 * (ADR 0079). Each window rolls each family with a random source seeded from the outpost, the window and the family, so a window's
 * result never depends on which windows were rolled with it: rolling {@code [a, c)} equals rolling {@code [a, b)} and {@code [b, c)}
 * and adding the counts, however a sweep or a restart splits the time.
 *
 * <p>A family that has nothing to take rolls nothing: an outpost with no dark corner, no fuel in storage, no ore and no blocks to lose is
 * safe from every visit.
 */
public final class VisitRoll {
    private static final int RETAINED = 0;
    private static final int SLEEPERS = 1;
    private static final int RECOVERY = 2;

    private VisitRoll() {}

    /** The seed of one outpost: the same outpost rolls the same visits in every run of the world, and no two outposts share them. */
    public static long outpostSeed(long worldSeed, CharterId charter, int layer) {
        long seed = mix(
                worldSeed,
                charter.value().getMostSignificantBits(),
                charter.value().getLeastSignificantBits());
        return mix(seed, layer, 0);
    }

    /**
     * The visits of windows {@code fromWindow} up to, not including, {@code toWindow}.
     *
     * @param draws what the outpost has to lose, from its last survey
     * @param rates how often each family comes to the outpost's layer
     */
    public static Pending roll(
            long outpostSeed, long fromWindow, long toWindow, Survey draws, LayerRates rates, SettlingTuning tuning) {
        double retainedChance =
                draws.dark() && draws.fuelItems() > 0 ? tuning.chancePerWindow(rates.retainedPerDay()) : 0;
        double sleepersChance =
                draws.noise() > 0 && draws.breakable() > 0 ? tuning.chancePerWindow(rates.sleepersPerDay()) : 0;
        double recoveryChance = draws.oreItems() > 0 ? tuning.chancePerWindow(rates.recoveryPerDay()) : 0;
        int retained = 0;
        int sleepers = 0;
        int recovery = 0;
        for (long window = fromWindow; window < toWindow; window++) {
            retained += visits(outpostSeed, window, RETAINED, retainedChance);
            sleepers += visits(outpostSeed, window, SLEEPERS, sleepersChance);
            recovery += visits(outpostSeed, window, RECOVERY, recoveryChance);
        }
        return new Pending(retained, sleepers, recovery);
    }

    /** One if the window's roll for the family comes up under {@code chance}, else none. */
    private static int visits(long outpostSeed, long window, int family, double chance) {
        if (chance <= 0) {
            return 0;
        }
        return RandomSource.create(mix(outpostSeed, window, family)).nextDouble() < chance ? 1 : 0;
    }

    /** A 64-bit hash of three numbers (SplitMix64's finaliser, folded in turn), so neighbouring windows have unrelated sources. */
    public static long mix(long a, long b, long c) {
        long hash = finish(a + 0x9E3779B97F4A7C15L);
        hash = finish(hash ^ (b + 0x9E3779B97F4A7C15L));
        return finish(hash ^ (c + 0x9E3779B97F4A7C15L));
    }

    private static long finish(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
