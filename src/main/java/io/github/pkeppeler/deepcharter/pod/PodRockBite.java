package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * The drill that bites Company rock (#526, ADR 0052): a pod whose drill works at {@link PodRockBiteTuning#minTier} or more bores a Company rock slab, slowly, with
 * the fuel rate multiplied and the hull heated while it cuts. The tier is read as {@link PodComponents#effectiveTier} says, so a chassis' cap limits it and a part of
 * another charter's is void. Any other drill still refuses the rock ({@link PodDrill}).
 */
public final class PodRockBite {
	private static final int TICKS_PER_SECOND = 20;

	private PodRockBite() {
	}

	/** True when the pod's drill is good enough, whatever its hull is. */
	static boolean canBite(PodEntity pod) {
		return PodComponents.effectiveTier(pod, ComponentTrack.DRILL) >= PodRockBiteTuning.DEFAULT.minTier();
	}

	/** True when the hull is at or under the floor, so the cutter will not run. */
	static boolean tooHot(PodEntity pod) {
		return pod.hull() <= PodRockBiteTuning.DEFAULT.hullFloor();
	}

	/** Ticks to bore a Company rock slab at {@code depthFeet}, as {@link PodDrill#drillTicks} says for any block. */
	public static int drillTicks(PodStats stats, int depthFeet) {
		return PodDrill.drillTicks(stats, PodRockBiteTuning.DEFAULT.hardness(), depthFeet);
	}

	/** One tick of the cutter's heat, less by the radiator. */
	static void heat(PodEntity pod) {
		pod.damageHull(PodRockBiteTuning.DEFAULT.heatHullPerSecond() / TICKS_PER_SECOND * PodComponents.radiatorRatio(pod));
	}

	/** The factor on the drilling fuel rate while a Company rock slab is under the cutter. */
	static float fuelMultiplier() {
		return PodRockBiteTuning.DEFAULT.fuelMultiplier();
	}
}
