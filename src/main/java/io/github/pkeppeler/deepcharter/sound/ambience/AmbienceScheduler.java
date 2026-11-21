package io.github.pkeppeler.deepcharter.sound.ambience;

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
 * </ul>
 */
public final class AmbienceScheduler {
    /** Where a fragment comes from. */
    public enum Source {
        DARK,
        HORN
    }

    /** A fragment due now: its tier, its name in the tier's pool, and where it sounds from. */
    public record Pick(AmbienceTier tier, String fragment, Source source) {}

    private static final int UNSET = -1;

    private final AmbienceTuning tuning;
    private final Map<AmbienceTier, List<String>> pools;
    private final RandomGenerator random;
    private int layer = 0;
    private int ticksLeft = UNSET;
    private int hornTicksLeft = UNSET;
    private @Nullable String last;

    /** @param pools the fragments of each tier, by name */
    public AmbienceScheduler(AmbienceTuning tuning, Map<AmbienceTier, List<String>> pools, RandomGenerator random) {
        this.tuning = tuning;
        this.pools = pools;
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
        AmbienceTier tier = rate.tier();
        String fragment = quiet ? null : choose(tier);
        if (fragment == null) {
            int retry = draw(tuning.retryMinTicks(), tuning.retryMaxTicks());
            if (hornNear) {
                hornTicksLeft = retry;
            } else {
                ticksLeft = retry;
            }
            return Optional.empty();
        }
        last = fragment;
        // Any fragment redraws both timers.
        ticksLeft = draw(rate.minTicks(), rate.maxTicks());
        if (hornNear) {
            hornTicksLeft = draw(
                    Math.max(tuning.hornMinTicks(), rate.minTicks()), Math.max(tuning.hornMaxTicks(), rate.maxTicks()));
        }
        return Optional.of(new Pick(tier, fragment, hornNear ? Source.HORN : Source.DARK));
    }

    /** A fragment of the tier other than the last, or null when the pool has nothing else to say. */
    private @Nullable String choose(AmbienceTier tier) {
        List<String> others = pools.getOrDefault(tier, List.of()).stream()
                .filter(name -> !name.equals(last))
                .toList();
        return others.isEmpty() ? null : others.get(random.nextInt(others.size()));
    }

    /** A whole number of ticks from {@code min} to {@code max}, both included. */
    private int draw(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
