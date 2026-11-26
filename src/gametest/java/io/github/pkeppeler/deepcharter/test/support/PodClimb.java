package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;

/** The load at which a pod stops climbing, worked out from the tuning {@code PodMovement.tick} reads (#1456). */
public final class PodClimb {
    private PodClimb() {}

    /**
     * The load from which a held jump no longer climbs: the thrust, {@code thrustAcceleration * lift / enginePower},
     * no longer beats gravity, at {@code enginePower * (1 - gravity / thrustAcceleration)}.
     */
    public static float limit(PodEntity pod) {
        PodStats stats = PodStats.of(pod);
        double gravity = pod.gravityPull(PodTuning.DEFAULT.movement().gravity());
        return (float) (stats.enginePower() * (1 - gravity / stats.thrustAcceleration()));
    }
}
