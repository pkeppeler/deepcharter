package io.github.pkeppeler.deepcharter.test;

/** The trail judgment of {@link PodMovementClientTest}, apart from the game so {@link TrailJudgeTest} runs the same code. */
final class TrailJudge {
    private TrailJudge() {}

    /** The most ticks in a row that {@code trail} is over {@code bound}. */
    static int longestRunOver(double[] trail, double bound) {
        int longest = 0;
        int run = 0;
        for (double blocks : trail) {
            run = blocks > bound ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }
}
