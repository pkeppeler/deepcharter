package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.util.Mth;

/**
 * The block-aligned cells a pod's box fills: the low corner of its square, its width and height in blocks, and the Y of its feet.
 * The drill bores a slab of it, and the pilot lines the rock around it.
 */
record PodFootprint(int lowX, int lowZ, int width, int height, int feetY) {
	/** Keeps a pod resting exactly on a block boundary on the block above it. */
	static final double EPSILON = 1e-3;

	static PodFootprint of(PodEntity pod) {
		Chassis chassis = pod.chassis();
		int width = chassis.boreWidth();
		int height = chassis.boreHeight();
		return new PodFootprint(Mth.floor(pod.getX() - width / 2.0 + 0.5), Mth.floor(pod.getZ() - width / 2.0 + 0.5), width, height,
				Mth.floor(pod.getY() + EPSILON));
	}
}
