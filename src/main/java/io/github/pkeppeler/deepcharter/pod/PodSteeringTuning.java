package io.github.pkeppeler.deepcharter.pod;

/**
 * How a pod steers, read as {@code PodSteeringTuning.DEFAULT.thing()}: tank controls (ADR 0095), squared to the grid
 * where it drills. It is a record of its own because {@link PodTuning} takes no more changes.
 *
 * @param turnSpeed degrees a tick the pod turns while A or D is held, from the first tick to the last, and 0 once let
 *     go
 * @param reverseShare share of the drive speed that S backs up at
 * @param alignDegrees degrees a tick the pod turns to the grid when the drill engages, or in a bore as wide as itself
 * @param alignTolerance degrees from the grid within which the pod counts as square to it and snaps onto it
 */
public record PodSteeringTuning(float turnSpeed, float reverseShare, float alignDegrees, float alignTolerance) {
    public static final PodSteeringTuning DEFAULT = new PodSteeringTuning(6f, 0.4f, 15f, 0.5f);
}
