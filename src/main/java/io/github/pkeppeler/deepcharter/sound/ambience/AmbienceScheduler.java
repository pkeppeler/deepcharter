package io.github.pkeppeler.deepcharter.sound.ambience;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * When a fragment is heard, and which: the one decision the client's ticker makes each tick, with no client in it. It is told the player's
 * layer, whether a Company horn is near, and whether something else is sounding.
 *
 * <ul>
 *   <li>The surface and the colony are never given one; a layer's gap is drawn at random between its two ends ({@link AmbienceTuning.Rate}),
 *       again after each fragment and on arriving in a layer, so there is no period to learn.
 *   <li>A fragment never follows itself: the next is drawn from the tier's pool without the last one, whichever tier it was.
 *   <li>A fragment that falls due while something is sounding (the Lamp Hymn, a phonograph, a transmission, another fragment) waits a short,
 *       drawn time and tries again; it is never played over them.
 *   <li>Near a horn the horn's timer replaces the layer's, and the fragment comes from the horn. The horn adds one fragment: the first
 *       after arriving, not sooner than a minute. After any fragment both timers are drawn again, and the horn's gap is never shorter than
 *       the layer's own, so a horn changes where a fragment is heard from and never how often.
 *   <li>A horn chooses among the layer's fragments and its own pool, which is not a tier and which the dark never draws from: more to
 *       hear from it, not more often. Each fragment of the two is as likely as the next.
 * </ul>
 */
public final class AmbienceScheduler {
    /** Where a fragment comes from. */
    public enum Source {
        DARK,
        HORN
    }

    /** Where a fragment is drawn from: a layer's tier pool, or the horn's own. */
    public enum Pool {
        LAYER,
        HORN
    }

    /** A fragment due now: the layer's tier, its name in {@code pool}, where it sounds from. The horn's pool is only ever heard from a horn. */
    public record Pick(AmbienceTier tier, String fragment, Source source, Pool pool) {
        public Pick {
            if (pool == Pool.HORN && source != Source.HORN) {
                throw new IllegalArgumentException("the horn's pool is only heard from a horn");
            }
        }
    }

    private static final int UNSET = -1;

    private final AmbienceTuning tuning;
    private final Map<AmbienceTier, List<String>> pools;
    private final List<String> hornPool;
    private final RandomGenerator random;
    private int layer = 0;
    private int ticksLeft = UNSET;
    private int hornTicksLeft = UNSET;
    private @Nullable String last;

    /**
     * @param pools    the fragments of each tier, by name
     * @param hornPool the fragments only a horn plays, by name
     */
    public AmbienceScheduler(
            AmbienceTuning tuning,
            Map<AmbienceTier, List<String>> pools,
            List<String> hornPool,
            RandomGenerator random) {
        this.tuning = tuning;
        this.pools = pools;
        this.hornPool = hornPool;
        this.random = random;
    }

    /** Forgets the timers, for a player who has left the world. The last fragment is kept: it still must not repeat. */
    public void reset() {
        layer = 0;
        ticksLeft = UNSET;
        hornTicksLeft = UNSET;
    }

    /** One client tick. Returns the fragment that is due, if one is and nothing holds it back. */
    public Optional<Pick> tick(int currentLayer, boolean hornNear, boolean quiet) {
        Optional<AmbienceTuning.Rate> found = tuning.rateFor(currentLayer);
        if (found.isEmpty()) {
            reset();
            return Optional.empty();
        }
        AmbienceTuning.Rate rate = found.get();
        if (currentLayer != layer || ticksLeft == UNSET) {
            layer = currentLayer;
            ticksLeft = draw(rate.minTicks(), rate.maxTicks());
        }
        if (!hornNear) {
            hornTicksLeft = UNSET;
        } else if (hornTicksLeft == UNSET) {
            // The one extra: a first fragment soon after arriving, whatever the layer's own timer has left.
            hornTicksLeft = draw(tuning.hornFirstMinTicks(), tuning.hornMaxTicks());
        }
        // Near a horn its timer is the only one that runs, so the horn never adds to the layer's rate.
        if ((hornNear ? --hornTicksLeft : --ticksLeft) > 0) {
            return Optional.empty();
        }
        Optional<Pick> chosen = quiet ? Optional.empty() : choose(rate.tier(), hornNear);
        if (chosen.isEmpty()) {
            int retry = draw(tuning.retryMinTicks(), tuning.retryMaxTicks());
            if (hornNear) {
                hornTicksLeft = retry;
            } else {
                ticksLeft = retry;
            }
            return Optional.empty();
        }
        Pick pick = chosen.get();
        last = pick.fragment();
        // Any fragment redraws both timers.
        ticksLeft = draw(rate.minTicks(), rate.maxTicks());
        if (hornNear) {
            hornTicksLeft = draw(
                    Math.max(tuning.hornMinTicks(), rate.minTicks()), Math.max(tuning.hornMaxTicks(), rate.maxTicks()));
        }
        return chosen;
    }

    /** A fragment of the tier other than the last, or empty when there is nothing else to say. Near a horn its own pool is added. */
    private Optional<Pick> choose(AmbienceTier tier, boolean hornNear) {
        Source source = hornNear ? Source.HORN : Source.DARK;
        List<Pick> others = new ArrayList<>();
        for (String name : pools.getOrDefault(tier, List.of())) {
            if (!name.equals(last)) {
                others.add(new Pick(tier, name, source, Pool.LAYER));
            }
        }
        if (hornNear) {
            for (String name : hornPool) {
                if (!name.equals(last)) {
                    others.add(new Pick(tier, name, source, Pool.HORN));
                }
            }
        }
        return others.isEmpty() ? Optional.empty() : Optional.of(others.get(random.nextInt(others.size())));
    }

    /** A whole number of ticks from {@code min} to {@code max}, both included. */
    private int draw(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
