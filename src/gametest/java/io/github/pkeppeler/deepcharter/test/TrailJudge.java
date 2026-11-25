package io.github.pkeppeler.deepcharter.test;

import java.util.Arrays;
import java.util.Optional;

/** The trail judgment of {@link PodMovementClientTest}, apart from the game so {@link TrailJudgeTest} runs the same code. */
final class TrailJudge {
    private TrailJudge() {}

    /**
     * Ticks the client's slide takes to bring a trail {@code gap} blocks over the steady one strictly under {@code margin}, the slide
     * closing 1/{@code steps} of its gap a tick: the first n with {@code gap * ((steps - 1) / steps)^n < margin}. A gap that lands
     * exactly on the margin at tick n is not under it yet, so it counts tick n + 1.
     */
    static int slideTicks(double gap, double margin, int steps) {
        return (int) Math.floor(Math.log(gap / margin) / Math.log(steps / (steps - 1.0))) + 1;
    }

    /** Why {@code trail} fails {@link PodMovementClientTest}'s limits, as {@link #summary}, or empty when it passes. */
    static Optional<String> fault(double[] trail) {
        boolean passes = longestRunOver(trail) <= PodMovementClientTest.MAX_TICKS_OVER
                && ticksOver(trail) <= PodMovementClientTest.MAX_TICKS_OVER_IN_ALL
                && worst(trail) <= PodMovementClientTest.MAX_WORST_BLOCKS;
        return passes ? Optional.empty() : Optional.of(summary(trail));
    }

    /** The run, the total ticks over and the worst tick of {@code trail}, each beside its limit. */
    static String summary(double[] trail) {
        return String.format(
                "over %s blocks for %s ticks in a row (allowed %s) and %s ticks in all (allowed %s), worst %s (allowed %s)",
                PodMovementClientTest.MAX_LAG_BLOCKS,
                longestRunOver(trail),
                PodMovementClientTest.MAX_TICKS_OVER,
                ticksOver(trail),
                PodMovementClientTest.MAX_TICKS_OVER_IN_ALL,
                worst(trail),
                PodMovementClientTest.MAX_WORST_BLOCKS);
    }

    private static double worst(double[] trail) {
        return Arrays.stream(trail).max().orElse(0);
    }

    /** The most ticks in a row that {@code trail} is over {@link PodMovementClientTest#MAX_LAG_BLOCKS}. */
    private static int longestRunOver(double[] trail) {
        int longest = 0;
        int run = 0;
        for (double blocks : trail) {
            run = blocks > PodMovementClientTest.MAX_LAG_BLOCKS ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    private static int ticksOver(double[] trail) {
        return (int) Arrays.stream(trail)
                .filter(blocks -> blocks > PodMovementClientTest.MAX_LAG_BLOCKS)
                .count();
    }
}
