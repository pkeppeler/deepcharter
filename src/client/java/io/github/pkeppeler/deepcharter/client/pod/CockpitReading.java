package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLoad;
import net.minecraft.world.level.Level;

/**
 * What the cockpit's five gauges read: the fuel tank, the hull out of the most the pod's parts allow, the depth out of the deepest floor of the layers and
 * the heat, each from 0 to 1, and the pod's load against its climb limit ({@link PodLoad}, #1449). The pod has no heat of its own: lava burning the hull is
 * all there is, so the heat is {@code heat}, which {@link CockpitHud} eases toward 1 while the hull burns and back to 0 after.
 *
 * @param fuel the tank, a share of full
 * @param hull the hull, a share of its most
 * @param depth the pod's depth below sea level, a share of the deepest floor
 * @param heat 1 when the hull burns and 0 when it does not, eased between
 * @param load the pod's load as a share of its climb limit, from 0 to {@link #MOST_LOAD}: 1 is the limit
 * @param overloaded the load is at or over the climb limit: the held rotor cannot climb
 */
public record CockpitReading(double fuel, double hull, double depth, double heat, double load, boolean overloaded) {
    /** The most fuel the tank holds, in the percent the pod counts it in. */
    private static final double FULL_TANK_PERCENT = 100.0;

    /** The highest load share the reading gives: it only bounds the value, and the gauge is full well before it. */
    private static final double MOST_LOAD = 2.0;

    public static CockpitReading of(PodEntity pod, double heat) {
        Level level = pod.level();
        double deepest = LayerChain.deepestDepth(level);
        PodLoad load = PodLoad.of(pod);
        return new CockpitReading(
                clamp(pod.fuel() / FULL_TANK_PERCENT),
                pod.maxHull() > 0f ? clamp(pod.hull() / pod.maxHull()) : 0.0,
                clamp(Depth.of(level, pod.getBlockY()) / deepest),
                clamp(heat),
                loadShare(load),
                load.cannotClimb());
    }

    private static double loadShare(PodLoad load) {
        if (load.climbLimit() <= 0f) {
            return load.load() > 0f ? MOST_LOAD : 0.0;
        }
        return Math.min(MOST_LOAD, (double) load.load() / load.climbLimit());
    }

    private static double clamp(double share) {
        return Math.max(0.0, Math.min(1.0, share));
    }
}
