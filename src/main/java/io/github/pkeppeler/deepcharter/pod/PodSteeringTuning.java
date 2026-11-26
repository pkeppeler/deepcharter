package io.github.pkeppeler.deepcharter.pod;

/**
 * How a pod steers, read as {@code PodSteeringTuning.DEFAULT.thing()}. The first two are vanilla's boat as it is
 * (ADR 0095); the rest are where a drilling pod differs from a boat. It is a record of its own because
 * {@link PodTuning} takes no more changes.
 *
 * @param turnAcceleration degrees a tick of turn rate that A or D adds each tick it is held (the boat's 1)
 * @param turnDamping share of the turn rate kept each tick, so it settles at {@code turnAcceleration / (1 -
 *     turnDamping)}, 10 degrees a tick, and keeps turning a while after the key is let go (the boat's 0.9)
 * @param reverseShare share of the drive speed that S backs up at. The boat backs up at an eighth of its throttle,
 *     which is too slow to back out of a bore
 * @param creepShare share of the drive speed a bare A or D drives forward at, as the boat's paddles do (the boat's
 *     1/8)
 * @param alignDegrees degrees a tick the pod turns to the grid when the drill engages, or in a bore as wide as itself
 * @param alignTolerance degrees from the grid within which the pod counts as square to it and snaps onto it
 */
public record PodSteeringTuning(
        float turnAcceleration,
        float turnDamping,
        float reverseShare,
        float creepShare,
        float alignDegrees,
        float alignTolerance) {
    public static final PodSteeringTuning DEFAULT = new PodSteeringTuning(1f, 0.9f, 0.4f, 0.125f, 15f, 0.5f);
}
