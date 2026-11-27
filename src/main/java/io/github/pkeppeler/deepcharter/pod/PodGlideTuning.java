package io.github.pkeppeler.deepcharter.pod;

/**
 * How the client draws a pod between the server's moves (ADR 0082). Read as {@code PodGlideTuning.DEFAULT.thing()}. It is a
 * second tuning record on purpose: {@link PodTuning} is frozen, and a tunable that only changes how the client draws a pod goes here.
 *
 * @param steps         ticks the client slides its copy over to each move; 2 absorbs a missed or doubled update (ADR 0074)
 * @param catchUp       the most a tick covers while the client is behind, as a multiple of the faster of the speed the server reports and
 *                      the stock drive speed: a pod that fell behind catches up at this pace, never in one lunge. Kept under the 1.4
 *                      frame-step bound of the drive tests ({@code PodDrives})
 * @param catchUpTicks  a gap is closed at no less than one share of this many ticks, so a large gap closes faster than {@code catchUp}
 *                      allows and the pace is continuous in the gap, with no cliff
 * @param coastTicks    ticks a pod keeps going at its reported speed with no move to follow, so a late server draws a pod that
 *                      keeps moving, not one that freezes and lunges; it never draws more than {@link #coastReach()} times the speed
 *                      past where the server put the pod
 * @param coastTailShare the share of its speed the last coast tick draws: a server one tick later than the full-speed coast slows the
 *                      pod instead of stopping it dead (#1112). Above the 0.5 floor of a steady tick in the drive tests ({@code PodDrives})
 * @param settleDelayTicks ticks with no move, while the server still reports the pod moving, before a pod that coasted too far goes
 *                      back to the server's last position: a stall that long means the pod has stopped there (a pin, a tow, a world
 *                      change), while a shorter one is a late server whose next move is the real answer, and going back first would
 *                      draw a reversal. Until then the pod goes on within the coast's reach, or stands when that is used up
 * @param settleShare   the share of its gap to the server's position a pod that coasted too far gives back each tick: a small, smooth
 *                      bounce back, not a snap
 * @param snapGap       blocks beyond which the pod is not eased but glides over {@code steps} ticks as before: a real teleport, or a
 *                      server so far behind that easing would trail the pilot for seconds
 * @param coastSweepSteps the most sub-steps a coast tick is swept in for collision: a step is swept in pieces no longer than the pod's
 *                      smallest side, and a huge synced speed (an explosion, a tow) is capped here, so it costs a bounded number of checks.
 *                      Real speeds need fewer
 */
public record PodGlideTuning(
        int steps,
        double catchUp,
        int catchUpTicks,
        int coastTicks,
        double coastTailShare,
        int settleDelayTicks,
        double settleShare,
        double snapGap,
        int coastSweepSteps) {
    public static final PodGlideTuning DEFAULT = new PodGlideTuning(2, 1.35, 12, 4, 0.6, 10, 0.12, 16.0, 8);

    public PodGlideTuning {
        if (!(catchUp > 1)) {
            throw new IllegalArgumentException(
                    "catchUp must exceed 1, was " + catchUp + ": PodGlide.splitCatchUp divides by cap - speed");
        }
    }

    /** Ticks of its speed a whole coast draws: the full-speed ticks and the tail's share of the last. */
    public double coastReach() {
        return coastTicks - 1 + coastTailShare;
    }
}
