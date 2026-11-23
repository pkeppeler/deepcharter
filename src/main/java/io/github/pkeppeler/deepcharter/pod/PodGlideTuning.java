package io.github.pkeppeler.deepcharter.pod;

/**
 * How the client draws a pod between the server's moves (ADR 0082). Read as {@code PodGlideTuning.DEFAULT.thing()}. It is a
 * second tuning record on purpose: {@link PodTuning} is frozen, and a tunable that only changes how the client draws a pod goes here.
 *
 * @param steps         ticks the client slides its copy over to each move; 2 absorbs a missed or doubled update (ADR 0074)
 * @param catchUp       the most a tick covers while the client is behind, as a multiple of the faster of the speed the server reports and
 *                      the stock drive speed: a pod that fell behind catches up at this pace, never in one lunge. Kept under the 1.4
 *                      frame-step bound of {@code PodDriveSmoothClientTest}
 * @param catchUpTicks  a gap is closed at no less than one share of this many ticks, so a large gap closes faster than {@code catchUp}
 *                      allows and the pace is continuous in the gap, with no cliff
 * @param coastTicks    ticks a pod keeps going at its reported speed with no move to follow, so a late server draws a pod that
 *                      keeps moving, not one that freezes and lunges; it never draws more than {@code coastTicks} times the speed past
 *                      where the server put the pod
 * @param settleDelayTicks ticks with no move, while the server still reports the pod moving, before a pod that coasted too far goes
 *                      back to the server's last position: a stall that long means the pod has stopped there (a pin, a tow, a world
 *                      change), while a shorter one is a late server whose next move is the real answer, and going back first would
 *                      draw a reversal
 * @param settleShare   the share of its gap to the server's position a pod that coasted too far gives back each tick: a small, smooth
 *                      bounce back, not a snap
 * @param snapGap       blocks beyond which the pod is not eased but glides over {@code steps} ticks as before: a real teleport, or a
 *                      server so far behind that easing would trail the pilot for seconds
 */
public record PodGlideTuning(
        int steps,
        double catchUp,
        int catchUpTicks,
        int coastTicks,
        int settleDelayTicks,
        double settleShare,
        double snapGap) {
    public static final PodGlideTuning DEFAULT = new PodGlideTuning(2, 1.35, 12, 2, 10, 0.2, 16.0);
}
