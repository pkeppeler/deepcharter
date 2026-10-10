package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for hand lining (#313), read as {@code PodLiningTuning.DEFAULT.thing()}. A drill keeps one spoil for each block of waste
 * rock it bores; the ore processor fuses spoil into slag brick; a seated pilot places the brick round the pod's slab.
 *
 * @param spoilCapacity  the most spoil a pod's bay keeps; a drill past it loses the rock, as it does ore past a full bay
 * @param spoilPerBrick  spoil the processor takes for one slag brick
 * @param brickCapacity  the most slag brick a pod's rack holds; the processor hands the rest to the buyer's inventory
 * @param fusePrice      dollars the processor charges for each brick it makes; 0 since #363: a brick costs 2 spoil and no money
 * @param ticksPerBrick  server ticks the pilot takes to place one brick, during which the pod holds still
 * @param spoilMass      pod mass of one spoil, which cuts lift like ore does
 * @param brickMass      pod mass of one brick in the rack
 */
public record PodLiningTuning(int spoilCapacity, int spoilPerBrick, int brickCapacity, int fusePrice, int ticksPerBrick,
		float spoilMass, float brickMass) {
	public static final PodLiningTuning DEFAULT = new PodLiningTuning(64, 2, 32, 0, 8, 0.1f, 0.2f);

	public PodLiningTuning {
		if (spoilCapacity < 1 || spoilPerBrick < 1 || brickCapacity < 1 || fusePrice < 0 || ticksPerBrick < 1) {
			throw new IllegalArgumentException("lining capacities, ratio and time must be at least 1, and the price not negative");
		}
		if (!(spoilMass >= 0f) || !(brickMass >= 0f)) {
			throw new IllegalArgumentException("lining masses must be numbers, not negative");
		}
	}
}
