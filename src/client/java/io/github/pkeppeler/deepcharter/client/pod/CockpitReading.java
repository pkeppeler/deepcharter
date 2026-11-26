package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.HardLanding;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import net.minecraft.world.level.Level;

/**
 * What the cockpit's five gauges read, each from 0 to 1: the fuel tank, the hull out of the most the pod's parts allow, the depth out of the deepest
 * floor of the layers, the heat, and the cargo bay. The pod has no heat of its own: lava burning the hull is all there is, so the heat is {@code heat}, which
 * {@link CockpitHud} eases toward 1 while the hull burns and back to 0 after.
 *
 * @param fuel the tank, a share of full
 * @param hull the hull, a share of its most
 * @param depth the pod's depth below sea level, a share of the deepest floor
 * @param heat 1 when the hull burns and 0 when it does not, eased between
 * @param cargo the ore aboard, a share of the bay's slots
 * @param sink the pod's sink as a share of its hard-landing buffer (#1432), up to {@link #MOST_SINK}: 1 is the buffer, and 0 on the ground or rising
 */
public record CockpitReading(double fuel, double hull, double depth, double heat, double cargo, double sink) {
    /** The most fuel the tank holds, in the percent the pod counts it in. */
    private static final double FULL_TANK_PERCENT = 100.0;

    /** The highest sink share the reading gives; past it every bar is full. */
    private static final double MOST_SINK = 4.0;

    public static CockpitReading of(PodEntity pod, double heat) {
        Level level = pod.level();
        double deepest = LayerChain.deepestDepth(level);
        return new CockpitReading(
                clamp(pod.fuel() / FULL_TANK_PERCENT),
                pod.maxHull() > 0f ? clamp(pod.hull() / pod.maxHull()) : 0.0,
                clamp(Depth.of(level, pod.getBlockY()) / deepest),
                clamp(heat),
                cargoShare(pod),
                sinkShare(pod));
    }

    /** The sink of the last tick, as the "HARD LANDING" line reads it: the client glides the pod and has no speed of its own to read. */
    private static double sinkShare(PodEntity pod) {
        if (pod.onGround()) {
            return 0.0;
        }
        double sink = HardLanding.sinkSpeed(pod.getY() - pod.yo);
        double buffer = PodStats.of(pod).hardLandingSpeed();
        if (buffer <= 0) {
            return sink > 0 ? MOST_SINK : 0.0;
        }
        return Math.min(MOST_SINK, sink / buffer);
    }

    private static double cargoShare(PodEntity pod) {
        int slots = PodStats.of(pod).cargoSlots();
        return slots > 0 ? clamp((double) pod.cargoUsed() / slots) : 0.0;
    }

    private static double clamp(double share) {
        return Math.max(0.0, Math.min(1.0, share));
    }
}
