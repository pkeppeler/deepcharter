package io.github.pkeppeler.deepcharter.client.pod;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.dimension.DimensionType;

import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;

/**
 * What the cockpit's four gauges read, each from 0 to 1: the fuel tank, the hull out of the most the pod's parts allow, the depth out of the deepest
 * floor of the layers, and the heat. The pod has no heat of its own: lava burning the hull is all there is, so the heat is {@code heat}, which
 * {@link CockpitHud} eases toward 1 while the hull burns and back to 0 after.
 *
 * @param fuel the tank, a share of full
 * @param hull the hull, a share of its most
 * @param depth the pod's depth below sea level, a share of the deepest floor
 * @param heat 1 when the hull burns and 0 when it does not, eased between
 */
public record CockpitReading(double fuel, double hull, double depth, double heat) {
	/** The most fuel the tank holds, in the percent the pod counts it in. */
	private static final double FULL_TANK_PERCENT = 100.0;

	public static CockpitReading of(Minecraft client, PodEntity pod, double heat) {
		double deepest = deepestBlocks(client.level.registryAccess());
		return new CockpitReading(clamp(pod.fuel() / FULL_TANK_PERCENT), pod.maxHull() > 0f ? clamp(pod.hull() / pod.maxHull()) : 0.0,
				clamp(Depth.of(client.level, pod.getBlockY()) / deepest), clamp(heat));
	}

	/** The depth, in blocks below sea level, of the floor of the last layer. */
	static double deepestBlocks(RegistryAccess registries) {
		int last = LayerChain.count(registries);
		Registry<DimensionType> types = registries.lookupOrThrow(Registries.DIMENSION_TYPE);
		return LayerChain.topDepth(registries, last) + types.getValueOrThrow(LayerChain.type(last)).height();
	}

	private static double clamp(double share) {
		return Math.max(0.0, Math.min(1.0, share));
	}
}
