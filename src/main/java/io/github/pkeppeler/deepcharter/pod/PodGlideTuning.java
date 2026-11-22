package io.github.pkeppeler.deepcharter.pod;

/**
 * How the client draws a pod between the server's moves (ADR 0082). Read as {@code PodGlideTuning.DEFAULT.thing()}.
 *
 * @param steps         ticks the client slides its copy over to each move; 2 absorbs a missed or doubled update (ADR 0074)
 * @param catchUp       the most a tick may cover while the client is behind, as a multiple of the speed the server reports: a pod that
 *                      fell behind catches up at this pace, never in one lunge
 * @param coastTicks    ticks a pod keeps going at its reported speed with no move to follow, so a late server draws a pod that
 *                      keeps moving, not one that freezes and lunges
 * @param maxCatchUpGap blocks beyond which the catch-up cap does not apply: a pod pushed, towed or teleported glides over, as before
 */
public record PodGlideTuning(int steps, double catchUp, int coastTicks, double maxCatchUpGap) {
    public static final PodGlideTuning DEFAULT = new PodGlideTuning(2, 1.25, 2, 2.0);
}
