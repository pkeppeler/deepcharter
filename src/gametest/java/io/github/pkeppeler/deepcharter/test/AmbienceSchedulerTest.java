package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceFragments;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceScheduler;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceScheduler.Pick;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceScheduler.Source;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceTier;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceTuning;
import io.github.pkeppeler.deepcharter.sound.ambience.QuietTracker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Server GameTests for #866: when the First Sounds ambience is heard and which fragment. The scheduler is pure (no client, a seeded random
 * in its seam), so each rule is run for a game day or more of ticks in a moment: the surface and colony get none, the rate rises with
 * depth and a layer deeper than the table is the most worn, a fragment never follows itself, the gaps vary, and nothing starts while a hymn,
 * a phonograph, a transmission or another fragment sounds. The pools are checked against the sound events, which holds the line that no
 * fragment is the wreck's song.
 */
public class AmbienceSchedulerTest {
    private static final Map<AmbienceTier, List<String>> POOLS = Map.of(
            AmbienceTier.FAINT, List.of("a", "b", "c"),
            AmbienceTier.WORN, List.of("a", "b", "c", "d"),
            AmbienceTier.BROKEN, List.of("a", "b"));

    /** A game day is 24,000 ticks. */
    private static final int DAYS = 40;

    private static AmbienceScheduler scheduler(long seed) {
        return new AmbienceScheduler(AmbienceTuning.DEFAULT, POOLS, List.of(), new SplittableRandom(seed));
    }

    /** The picks of {@code ticks} ticks in one layer, and the tick of each. */
    private static List<Long> picksAt(
            AmbienceScheduler scheduler, int layer, boolean horn, boolean quiet, long ticks, List<Pick> picks) {
        List<Long> at = new ArrayList<>();
        for (long tick = 0; tick < ticks; tick++) {
            Optional<Pick> pick = scheduler.tick(layer, horn, quiet);
            if (pick.isPresent()) {
                picks.add(pick.get());
                at.add(tick);
            }
        }
        return at;
    }

    private static List<Long> gaps(List<Long> at) {
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < at.size(); i++) {
            gaps.add(at.get(i) - at.get(i - 1));
        }
        return gaps;
    }

    @GameTest
    public void theSurfaceAndTheColonyAreNeverGivenAFragment(GameTestHelper helper) {
        List<Pick> picks = new ArrayList<>();
        picksAt(scheduler(1), 0, false, false, DAYS * 24_000L, picks);
        expect(helper, picks.isEmpty(), "the surface was given %s fragments", picks.size());
        // A player at the colony stands beside a horn in no structure, but a horn wrongly near still gives nothing on
        // layer 0.
        picksAt(scheduler(2), 0, true, false, DAYS * 24_000L, picks);
        expect(helper, picks.isEmpty(), "the surface beside a horn was given %s fragments", picks.size());
        helper.succeed();
    }

    @GameTest
    public void theRateRisesWithDepthAndEachDepthHasItsTier(GameTestHelper helper) {
        int previous = 0;
        AmbienceTier[] tiers = {AmbienceTier.FAINT, AmbienceTier.WORN, AmbienceTier.BROKEN};
        for (int layer = 1; layer <= 3; layer++) {
            List<Pick> picks = new ArrayList<>();
            picksAt(scheduler(10 + layer), layer, false, false, DAYS * 24_000L, picks);
            expect(
                    helper,
                    picks.size() > previous,
                    "layer %s heard %s, no more than %s above",
                    layer,
                    picks.size(),
                    previous);
            for (Pick pick : picks) {
                expect(helper, pick.tier() == tiers[layer - 1], "layer %s heard a %s fragment", layer, pick.tier());
                expect(helper, pick.source() == Source.DARK, "layer %s heard a horn that is not there", layer);
            }
            previous = picks.size();
        }
        // The first layer is rare: at most 1 in 15 minutes (18,000 ticks), so under 1.3 a game day (24,000 ticks) and
        // well under 100 in 40.
        List<Pick> first = new ArrayList<>();
        picksAt(scheduler(99), 1, false, false, DAYS * 24_000L, first);
        expect(
                helper,
                first.size()
                        <= DAYS
                                * 24_000L
                                / AmbienceTuning.DEFAULT.layers().getFirst().minTicks(),
                "layer 1 heard %s fragments",
                first.size());
        helper.succeed();
    }

    @GameTest
    public void aLayerDeeperThanTheTableIsTheMostWornWithNoCodeChange(GameTestHelper helper) {
        List<AmbienceTuning.Rate> layers = AmbienceTuning.DEFAULT.layers();
        AmbienceTuning.Rate last = layers.getLast();
        expect(helper, last.tier() == AmbienceTier.BROKEN, "the deepest rate is %s", last.tier());
        for (int layer : new int[] {layers.size(), layers.size() + 1, 25}) {
            Optional<AmbienceTuning.Rate> rate = AmbienceTuning.DEFAULT.rateFor(layer);
            expect(helper, rate.isPresent() && rate.get() == last, "layer %s has %s", layer, rate);
        }
        expect(helper, AmbienceTuning.DEFAULT.rateFor(0).isEmpty(), "layer 0 has a rate");
        expect(helper, AmbienceTuning.DEFAULT.rateFor(-1).isEmpty(), "layer -1 has a rate");
        List<Pick> picks = new ArrayList<>();
        picksAt(scheduler(5), 40, false, false, DAYS * 24_000L, picks);
        expect(helper, !picks.isEmpty(), "a layer far deeper than any heard nothing");
        picks.forEach(pick -> expect(helper, pick.tier() == AmbienceTier.BROKEN, "layer 40 heard %s", pick.tier()));
        helper.succeed();
    }

    @GameTest
    public void aFragmentIsNeverHeardTwiceInARow(GameTestHelper helper) {
        // Fast rates and a pool of two, to meet every choice often; then the real table.
        AmbienceTuning quick = new AmbienceTuning(
                List.of(new AmbienceTuning.Rate(AmbienceTier.BROKEN, 1, 3)),
                1,
                2,
                0,
                List.of(),
                24,
                1,
                2,
                1,
                26,
                12,
                28,
                16);
        for (AmbienceTuning tuning : List.of(quick, AmbienceTuning.DEFAULT)) {
            AmbienceScheduler scheduler = new AmbienceScheduler(tuning, POOLS, List.of(), new SplittableRandom(3));
            List<Pick> picks = new ArrayList<>();
            picksAt(scheduler, 2, false, false, tuning == quick ? 20_000 : 4_000_000, picks);
            expect(helper, picks.size() > 100, "only %s picks to compare", picks.size());
            for (int i = 1; i < picks.size(); i++) {
                expect(
                        helper,
                        !picks.get(i).fragment().equals(picks.get(i - 1).fragment()),
                        "pick %s repeats the fragment %s",
                        i,
                        picks.get(i).fragment());
            }
            Set<String> heard = new HashSet<>();
            picks.forEach(pick -> heard.add(pick.fragment()));
            expect(helper, heard.size() > 1, "only %s was ever heard", heard);
        }
        helper.succeed();
    }

    @GameTest
    public void aFragmentDoesNotRepeatAcrossALayerChangeEither(GameTestHelper helper) {
        // Pools of one name per tier: the last heard is the only candidate, so nothing may be played, and nothing
        // is stuck.
        Map<AmbienceTier, List<String>> single = Map.of(
                AmbienceTier.FAINT,
                List.of("only"),
                AmbienceTier.WORN,
                List.of("only"),
                AmbienceTier.BROKEN,
                List.of("only"));
        AmbienceScheduler scheduler =
                new AmbienceScheduler(AmbienceTuning.DEFAULT, single, List.of(), new SplittableRandom(4));
        List<Pick> picks = new ArrayList<>();
        picksAt(scheduler, 1, false, false, 10 * 24_000L, picks);
        picksAt(scheduler, 2, false, false, 10 * 24_000L, picks);
        expect(helper, picks.size() == 1, "a pool of one played %s times; it may play once", picks.size());
        helper.succeed();
    }

    @GameTest
    public void theGapsBetweenFragmentsVaryAndStayWithinTheirBounds(GameTestHelper helper) {
        for (int layer = 1; layer <= 3; layer++) {
            AmbienceTuning.Rate rate = AmbienceTuning.DEFAULT.rateFor(layer).orElseThrow();
            List<Pick> picks = new ArrayList<>();
            List<Long> gaps = gaps(picksAt(scheduler(20 + layer), layer, false, false, 400 * 24_000L, picks));
            expect(helper, gaps.size() >= 30, "layer %s: only %s gaps", layer, gaps.size());
            Set<Long> distinct = new HashSet<>(gaps);
            expect(
                    helper,
                    distinct.size() > gaps.size() / 2,
                    "layer %s: %s gaps of %s differ",
                    layer,
                    distinct.size(),
                    gaps.size());
            for (long gap : gaps) {
                expect(
                        helper,
                        gap >= rate.minTicks(),
                        "layer %s: a gap of %s is under the %s minimum",
                        layer,
                        gap,
                        rate.minTicks());
                expect(
                        helper,
                        gap <= rate.maxTicks(),
                        "layer %s: a gap of %s is over the %s maximum",
                        layer,
                        gap,
                        rate.maxTicks());
            }
            long spread = gaps.stream().mapToLong(Long::longValue).max().orElseThrow()
                    - gaps.stream().mapToLong(Long::longValue).min().orElseThrow();
            expect(
                    helper,
                    spread > (rate.maxTicks() - rate.minTicks()) / 2,
                    "layer %s: the gaps spread only %s ticks",
                    layer,
                    spread);
        }
        helper.succeed();
    }

    @GameTest
    public void nothingStartsWhileSomethingElseSoundsAndItComesBackAfter(GameTestHelper helper) {
        AmbienceScheduler scheduler = scheduler(6);
        List<Pick> picks = new ArrayList<>();
        picksAt(scheduler, 2, false, true, DAYS * 24_000L, picks);
        expect(helper, picks.isEmpty(), "%s fragments played over a hymn or a phonograph", picks.size());
        // The fragments that fell due were held, and one comes back once the sound has ended, within a retry's
        // longest wait.
        picksAt(scheduler, 2, false, false, AmbienceTuning.DEFAULT.retryMaxTicks() + 1L, picks);
        expect(helper, picks.size() >= 1, "no fragment came back once the sound ended");
        helper.succeed();
    }

    @GameTest
    public void nearAHornTheFragmentsComeFromTheHornNotSoonerThanAMinuteAfterArriving(GameTestHelper helper) {
        AmbienceTuning tuning = AmbienceTuning.DEFAULT;
        for (long seed = 40; seed < 60; seed++) {
            List<Pick> picks = new ArrayList<>();
            List<Long> at = picksAt(scheduler(seed), 2, true, false, tuning.hornMaxTicks() + 2L, picks);
            expect(
                    helper,
                    picks.size() >= 1,
                    "seed %s: nothing came from the horn within %s ticks",
                    seed,
                    tuning.hornMaxTicks());
            expect(
                    helper,
                    picks.getFirst().source() == Source.HORN,
                    "seed %s: the fragment did not come from the horn",
                    seed);
            expect(
                    helper,
                    at.getFirst() >= tuning.hornFirstMinTicks() - 1,
                    "seed %s: the horn spoke at %s",
                    seed,
                    at.getFirst());
        }
        List<Pick> picks = new ArrayList<>();
        picksAt(scheduler(7), 2, true, false, DAYS * 24_000L, picks);
        picks.forEach(
                pick -> expect(helper, pick.source() == Source.HORN, "a fragment near a horn came from the dark"));
        helper.succeed();
    }

    /** A horn's own pool (#946) is played by the horn only: the dark is never given it, and near a horn it is one choice among many. */
    @GameTest
    public void aHornAddsItsOwnPoolToItsChoicesAndTheDarkNeverPlaysIt(GameTestHelper helper) {
        List<String> hornPool = List.of("h1", "h2", "h3");
        long ticks = DAYS * 24_000L;
        List<Pick> dark = new ArrayList<>();
        picksAt(
                new AmbienceScheduler(AmbienceTuning.DEFAULT, POOLS, hornPool, new SplittableRandom(3)),
                2,
                false,
                false,
                ticks,
                dark);
        expect(helper, dark.size() > 50, "only %s fragments in the dark", dark.size());
        dark.forEach(pick -> expect(
                helper,
                pick.pool() == AmbienceScheduler.Pool.LAYER && pick.source() == Source.DARK,
                "the dark played %s from the %s pool",
                pick.fragment(),
                pick.pool()));
        List<Pick> horn = new ArrayList<>();
        picksAt(
                new AmbienceScheduler(AmbienceTuning.DEFAULT, POOLS, hornPool, new SplittableRandom(4)),
                2,
                true,
                false,
                ticks,
                horn);
        long fromPool = horn.stream()
                .filter(pick -> pick.pool() == AmbienceScheduler.Pool.HORN)
                .count();
        expect(
                helper,
                fromPool > 0 && fromPool < horn.size(),
                "a horn played %s of %s from its pool",
                fromPool,
                horn.size());
        // Three of seven choices are the horn's own, each as likely as the next: about 43 per cent, give or take.
        double share = (double) fromPool / horn.size();
        expect(helper, share > 0.3 && share < 0.56, "the horn's pool is %s of what it plays", share);
        for (Pick pick : horn) {
            List<String> from = pick.pool() == AmbienceScheduler.Pool.HORN
                    ? hornPool
                    : POOLS.getOrDefault(AmbienceTier.WORN, List.of());
            expect(
                    helper,
                    from.contains(pick.fragment()) && pick.source() == Source.HORN,
                    "%s is not in the %s pool",
                    pick.fragment(),
                    pick.pool());
        }
        // The pool adds variety and not frequency: the horn's pace is the same with it as without it.
        List<Pick> without = new ArrayList<>();
        picksAt(scheduler(4), 2, true, false, ticks, without);
        expect(
                helper,
                Math.abs(horn.size() - without.size()) <= without.size() / 10 + 1,
                "a horn played %s with its pool and %s without",
                horn.size(),
                without.size());
        helper.succeed();
    }

    /** A pick from the horn's pool that does not come from a horn cannot be made: the dark has no way to name that pool. */
    @GameTest
    public void aPickFromTheHornsPoolMustComeFromAHorn(GameTestHelper helper) {
        boolean refused = false;
        try {
            new Pick(AmbienceTier.WORN, "h1", Source.DARK, AmbienceScheduler.Pool.HORN);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        expect(helper, refused, "a dark pick from the horn's pool was made");
        helper.succeed();
    }

    /** The shipped horn pool: these fragments, each a sound event of {@code ambience.horn.}, and none in a layer's pool. */
    @GameTest
    public void theShippedHornPoolIsTheFourFragmentsAndNoLayerPlaysThem(GameTestHelper helper) {
        List<String> horn = AmbienceFragments.hornPool();
        expect(
                helper,
                Set.copyOf(horn).equals(Set.of("diapason", "railway", "test_voices", "barometer")),
                "the horn pool is %s",
                horn);
        for (String name : horn) {
            expect(
                    helper,
                    AmbienceFragments.hornSound(name).location().getPath().equals("ambience.horn." + name),
                    "%s is not an ambience.horn event",
                    name);
            for (AmbienceTier tier : AmbienceTier.values()) {
                expect(
                        helper,
                        !AmbienceFragments.pools().getOrDefault(tier, List.of()).contains(name),
                        "%s is also in the %s pool",
                        name,
                        tier);
            }
        }
        helper.succeed();
    }

    /** The deep fragments (#946) are in the broken pool and in no shallower one. */
    @GameTest
    public void theDeepFragmentsAreOnlyInTheBrokenPool(GameTestHelper helper) {
        for (String name : List.of("ear_a", "donders_ki", "morey_oo", "scale_crosstalk", "choir_a", "choir_b")) {
            expect(
                    helper,
                    AmbienceFragments.pools()
                            .getOrDefault(AmbienceTier.BROKEN, List.of())
                            .contains(name),
                    "%s is not in the broken pool",
                    name);
            for (AmbienceTier tier : List.of(AmbienceTier.FAINT, AmbienceTier.WORN)) {
                expect(
                        helper,
                        !AmbienceFragments.pools().getOrDefault(tier, List.of()).contains(name),
                        "%s is in the %s pool",
                        name,
                        tier);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void aHornNeverMakesALayerNoisierThanItsOwnRate(GameTestHelper helper) {
        long ticks = DAYS * 24_000L;
        for (int layer = 1; layer <= 3; layer++) {
            AmbienceTuning.Rate rate = AmbienceTuning.DEFAULT.rateFor(layer).orElseThrow();
            long near = 0;
            long away = 0;
            for (long seed = 70; seed < 75; seed++) {
                List<Pick> picks = new ArrayList<>();
                List<Long> at = picksAt(scheduler(seed), layer, true, false, ticks, picks);
                // The first fragment after arriving is the one extra; every gap after it is at least the layer's
                // own minimum.
                expect(
                        helper,
                        picks.size() <= ticks / rate.minTicks() + 1,
                        "layer %s near a horn heard %s",
                        layer,
                        picks.size());
                for (long gap : gaps(at)) {
                    expect(
                            helper,
                            gap >= rate.minTicks(),
                            "layer %s: two fragments %s ticks apart near a horn",
                            layer,
                            gap);
                }
                near += picks.size();
                List<Pick> dark = new ArrayList<>();
                picksAt(scheduler(seed), layer, false, false, ticks, dark);
                away += dark.size();
            }
            expect(
                    helper,
                    near <= away * 1.1,
                    "layer %s: %s fragments near a horn against %s away from one",
                    layer,
                    near,
                    away);
        }
        helper.succeed();
    }

    @GameTest
    public void leavingAHornGoesBackToTheLayersOwnGapAndTheHornTimerStartsAgain(GameTestHelper helper) {
        AmbienceScheduler scheduler = scheduler(8);
        List<Pick> picks = new ArrayList<>();
        picksAt(scheduler, 2, true, false, 10, picks);
        // Away for longer than the horn's longest wait but shorter than the layer's shortest gap: nothing is heard from
        // the dark.
        picksAt(
                scheduler,
                2,
                false,
                false,
                AmbienceTuning.DEFAULT.layers().get(1).minTicks() - 100,
                picks);
        expect(helper, picks.isEmpty(), "%s fragments came before the layer's own gap", picks.size());
        helper.succeed();
    }

    @GameTest
    public void leavingTheWorldForgetsTheTimersButNotTheLastFragment(GameTestHelper helper) {
        AmbienceTuning quick = new AmbienceTuning(
                List.of(new AmbienceTuning.Rate(AmbienceTier.WORN, 5, 5)),
                1,
                1,
                0,
                List.of(),
                24,
                5,
                5,
                5,
                26,
                12,
                28,
                16);
        Map<AmbienceTier, List<String>> two = Map.of(AmbienceTier.WORN, List.of("x", "y"));
        AmbienceScheduler scheduler = new AmbienceScheduler(quick, two, List.of(), new SplittableRandom(9));
        List<Pick> picks = new ArrayList<>();
        picksAt(scheduler, 1, false, false, 6, picks);
        expect(helper, picks.size() == 1, "expected one pick, got %s", picks.size());
        String first = picks.getFirst().fragment();
        scheduler.reset();
        picksAt(scheduler, 1, false, false, 6, picks);
        expect(helper, picks.size() == 2, "expected a second pick after the reset, got %s", picks.size());
        expect(helper, !picks.get(1).fragment().equals(first), "the fragment repeated across a reset");
        helper.succeed();
    }

    @GameTest
    public void theQuietTrackerHoldsWhileASoundPlaysAndForAGraceAfter(GameTestHelper helper) {
        Set<String> playing = new HashSet<>();
        QuietTracker<String> tracker = new QuietTracker<>(playing::contains, 200);
        expect(helper, !tracker.quiet(1_000), "quiet with nothing ever played");
        playing.add("hymn");
        tracker.started("hymn", 1_000);
        expect(helper, tracker.quiet(1_001) && tracker.quiet(5_000), "not quiet while the hymn plays");
        playing.remove("hymn");
        expect(helper, tracker.quiet(5_100), "not quiet in the grace after it ended");
        expect(helper, !tracker.quiet(5_200), "still quiet after the grace");
        // A sound that began and ended between two checks is still heard.
        tracker.started("blip", 6_000);
        expect(helper, tracker.quiet(6_050), "a sound that ended between ticks was missed");
        tracker.clear();
        expect(helper, !tracker.quiet(6_051), "clear left the tracker quiet");
        helper.succeed();
    }

    @GameTest
    public void thePoolsHoldTheFragmentsOfTheirTierAndNoneIsTheWrecksSong(GameTestHelper helper) {
        Map<AmbienceTier, List<String>> pools = AmbienceFragments.pools();
        for (AmbienceTier tier : AmbienceTier.values()) {
            List<String> pool = pools.getOrDefault(tier, List.of());
            expect(helper, pool.size() >= 4, "the %s pool is %s", tier, pool);
            expect(helper, new HashSet<>(pool).size() == pool.size(), "the %s pool repeats a fragment: %s", tier, pool);
            for (String fragment : pool) {
                String path = AmbienceFragments.sound(tier, fragment).location().getPath();
                // The punch clock's deep fragments (#938) are the one voice of the broken tier that is named for no
                // fragment of it.
                boolean clock = tier == AmbienceTier.BROKEN && fragment.equals("clock");
                expect(
                        helper,
                        path.equals(
                                clock
                                        ? "ambience.clock.fragment"
                                        : "ambience." + tier.name().toLowerCase(Locale.ROOT) + "." + fragment),
                        "%s %s is the sound %s",
                        tier,
                        fragment,
                        path);
                expect(helper, !fragment.equals("horn_warmup"), "the horn's warm-up is in the %s pool", tier);
            }
        }
        expect(
                helper,
                pools.getOrDefault(AmbienceTier.FAINT, List.of()).size()
                        < pools.getOrDefault(AmbienceTier.WORN, List.of()).size(),
                "the first layer hears as many fragments as the Old Workings");
        for (DeepSound sound : DeepSound.values()) {
            String path = sound.event().location().getPath();
            if (path.startsWith("ambience.")) {
                expect(helper, !path.contains("clair") && !path.contains("lune"), "%s is the wreck's song", path);
            }
        }
        // The song's two variants (#861) are events of their own and sit in no tier's pool: the random ambience never
        // plays them.
        for (DeepSound song : List.of(DeepSound.SONG_WRECK, DeepSound.SONG_DARK)) {
            String path = song.event().location().getPath();
            expect(helper, !path.startsWith("ambience."), "%s is an ambience event", path);
            for (AmbienceTier tier : AmbienceTier.values()) {
                for (String fragment : pools.getOrDefault(tier, List.of())) {
                    expect(
                            helper,
                            AmbienceFragments.sound(tier, fragment) != song.event(),
                            "%s is in the %s pool",
                            path,
                            tier);
                }
            }
        }
        helper.succeed();
    }
}
