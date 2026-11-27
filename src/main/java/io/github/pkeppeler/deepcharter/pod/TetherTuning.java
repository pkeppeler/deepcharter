package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the tether (#986), read as {@code TetherTuning.DEFAULT.thing()}.
 *
 * @param length            blocks of line a person on foot can pay out from the winch: past it the line goes taut and holds them
 * @param snapMargin        blocks past {@link #length} at which a taut line snaps instead of holding (a teleport, or a snag the person walked on from)
 * @param clipReach         blocks from the pod within which a person can clip on
 * @param winchSpeed        blocks a tick the winch hauls a person toward the pod, and the most a taut line pulls back a tick
 * @param arriveGap         blocks between the pod's hull and the person at which the winch stops, so a person is never reeled into the hull
 * @param snagTicks         ticks the winch may haul against a line that rock lies across before the line snaps
 * @param snapCooldownTicks ticks a snapped tether cannot be clipped on again
 * @param noiseInterval     ticks between two loud clanks of a winch that is hauling
 * @param noiseRadius       blocks the clank carries, as the pull of the winch's decoy in {@code DrawSources}
 * @param stallTicks        ticks of hauling over which a person must close on the pod by {@link #stallProgress}, or the haul counts as snagged
 * @param stallProgress     blocks of line a person must gain on the pod over {@link #stallTicks}: less means the person is stuck on something the line's ray missed
 */
public record TetherTuning(
        double length,
        double snapMargin,
        double clipReach,
        double winchSpeed,
        double arriveGap,
        int snagTicks,
        int snapCooldownTicks,
        int noiseInterval,
        double noiseRadius,
        int stallTicks,
        double stallProgress) {
    public static final TetherTuning DEFAULT = new TetherTuning(24.0, 3.0, 6.0, 0.5, 1.0, 40, 200, 10, 40.0, 20, 0.05);

    public TetherTuning {
        if (!(length > 0)) {
            throw new IllegalArgumentException("the line length must be positive, got " + length);
        }
        if (!(snapMargin >= 0)) {
            throw new IllegalArgumentException("the snap margin must not be negative, got " + snapMargin);
        }
        if (!(clipReach > 0)) {
            throw new IllegalArgumentException("the clip reach must be positive, got " + clipReach);
        }
        if (!(winchSpeed > 0)) {
            throw new IllegalArgumentException("the winch speed must be positive, got " + winchSpeed);
        }
        if (!(arriveGap >= 0)) {
            throw new IllegalArgumentException("the arrive gap must not be negative, got " + arriveGap);
        }
        if (snagTicks < 1 || snapCooldownTicks < 0 || noiseInterval < 1) {
            throw new IllegalArgumentException(
                    "the snag ticks and the noise interval must be at least 1, and the cooldown not negative");
        }
        if (stallTicks < 1 || !(stallProgress >= 0)) {
            throw new IllegalArgumentException(
                    "the stall window must be at least 1 tick and its progress not negative, got " + stallTicks
                            + " and " + stallProgress);
        }
        if (!(noiseRadius >= 0)) {
            throw new IllegalArgumentException("the noise radius must not be negative, got " + noiseRadius);
        }
    }
}
