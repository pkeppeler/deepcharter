package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.Drifter;
import io.github.pkeppeler.deepcharter.creature.DrifterTuning;
import io.github.pkeppeler.deepcharter.creature.DrifterVariant;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;

/**
 * The form of a Drifter (#1204): a drifter made by hand is a jellyfish, a pick by the spawn weights is the same under the same seed, comes up
 * about in proportion to its weight and never for a weight of zero. {@link DrifterSpawnTest} proves that {@code Drifters.spawnFor} makes the pick.
 */
public class DrifterVariantTest {
    private static final long SEED = 1204L;
    private static final int DRAWS = 30_000;
    /** A third of {@link #DRAWS} is within this share of it: the spread of one share over that many draws is under 0.003. */
    private static final double TOLERANCE = 0.02;

    private static Map<DrifterVariant, Integer> tally(List<Double> weights, long seed) {
        RandomSource random = RandomSource.create(seed);
        Map<DrifterVariant, Integer> counts = new EnumMap<>(DrifterVariant.class);
        for (int i = 0; i < DRAWS; i++) {
            counts.merge(DrifterVariant.pick(random, weights), 1, Integer::sum);
        }
        return counts;
    }

    /** What {@code /summon} makes: no variant was set, so it is the jellyfish. */
    @GameTest
    public void aSummonedDrifterIsAJellyfish(GameTestHelper helper) {
        Drifter drifter = TestEntities.create(CreatureRegistry.DRIFTER, helper.getLevel());
        expect(helper, drifter.variant() == DrifterVariant.JELLYFISH, "a new drifter is a %s", drifter.variant());
        helper.succeed();
    }

    @GameTest
    public void theShippedWeightsGiveEachFormAboutAThird(GameTestHelper helper) {
        List<Double> weights = DrifterTuning.DEFAULT.spawn().variantWeights();
        Map<DrifterVariant, Integer> counts = tally(weights, SEED);
        for (DrifterVariant variant : DrifterVariant.values()) {
            double share = counts.getOrDefault(variant, 0) / (double) DRAWS;
            expect(
                    helper,
                    Math.abs(share - 1.0 / 3.0) < TOLERANCE,
                    "%s came up %s of %d draws, not about a third",
                    variant,
                    share,
                    DRAWS);
        }
        helper.succeed();
    }

    @GameTest
    public void aPickFollowsItsWeightsAndItsSeed(GameTestHelper helper) {
        List<Double> weights = List.of(1.0, 0.0, 3.0);
        Map<DrifterVariant, Integer> counts = tally(weights, SEED);
        expect(helper, !counts.containsKey(DrifterVariant.SAC), "a weight of 0 came up %s times", counts);
        double chain = counts.getOrDefault(DrifterVariant.CHAIN, 0) / (double) DRAWS;
        expect(helper, Math.abs(chain - 0.75) < TOLERANCE, "the chain, weight 3 of 4, came up %s of the draws", chain);
        expect(helper, tally(weights, SEED).equals(counts), "the same seed drew a different mix");
        List<DrifterVariant> first = new ArrayList<>();
        List<DrifterVariant> second = new ArrayList<>();
        RandomSource a = RandomSource.create(SEED);
        RandomSource b = RandomSource.create(SEED);
        for (int i = 0; i < 100; i++) {
            first.add(DrifterVariant.pick(a, weights));
            second.add(DrifterVariant.pick(b, weights));
        }
        expect(helper, first.equals(second), "the same seed drew a different sequence");
        helper.succeed();
    }

    /** An id past the last form (a newer server's) draws the jellyfish and does not throw on a join. */
    @GameTest
    public void anUnknownIdIsAJellyfish(GameTestHelper helper) {
        expect(helper, DrifterVariant.byOrdinal(-1) == DrifterVariant.JELLYFISH, "-1 is not a jellyfish");
        expect(
                helper,
                DrifterVariant.byOrdinal(DrifterVariant.values().length) == DrifterVariant.JELLYFISH,
                "an id past the last is not a jellyfish");
        expect(
                helper,
                DrifterVariant.byOrdinal(DrifterVariant.CHAIN.ordinal()) == DrifterVariant.CHAIN,
                "chain is lost");
        helper.succeed();
    }
}
