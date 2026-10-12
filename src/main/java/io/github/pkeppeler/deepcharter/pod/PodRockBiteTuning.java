package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the drill that bites Company rock (#526, ADR 0052), read as {@code PodRockBiteTuning.DEFAULT.thing()}.
 *
 * @param minTier           the drill tier, as the chassis' cap leaves it, from which the drill bores Company rock; below it the drill refuses (ADR 0050)
 * @param hardness          the hardness the drill's timing reads for Company rock (stone is 1.5), so a slab takes {@code hardness x ticksPerHardness} ticks at the surface
 * @param fuelMultiplier    how many times the drilling fuel rate burns while a Company rock slab is under the cutter
 * @param heatHullPerSecond hull points the cutter's heat costs each second of a bite, before the radiator's ratio
 * @param hullFloor         a pod whose hull is at or under this does not bite: the cutter runs hot, so the drill is never a way to burn the hull to nothing
 */
public record PodRockBiteTuning(int minTier, float hardness, float fuelMultiplier, float heatHullPerSecond, float hullFloor) {
	/**
	 * (A) Tier 3 is the Prospector's best drill (a Mole is capped at 2, so it never bites), the one tier a shipped chassis can reach. A tier 3 drill takes 6.4
	 * ticks for each point of hardness, so hardness 15 is 96 ticks, 4.8 s at the surface, against 0.5 s for stone: ten times slower. Fuel burns at three times the
	 * drilling rate and the cutter costs half a hull a second, so a slab costs about $13 at layer 2 depth against $20 a slab of a blasting charge
	 * (EconomyAffordabilityTest). A hull of 20 or less stops the bite, as it stops a charge.
	 */
	public static final PodRockBiteTuning DEFAULT = new PodRockBiteTuning(3, 15f, 3f, 0.5f, 20f);

	public PodRockBiteTuning {
		if (minTier < 1) {
			throw new IllegalArgumentException("a drill that bites needs a tier of 1 or more, got " + minTier);
		}
		if (!(hardness > 0f) || !(fuelMultiplier >= 1f) || !(heatHullPerSecond >= 0f) || !(hullFloor >= 0f)) {
			throw new IllegalArgumentException("a bite needs a hardness above 0, a fuel multiplier of 1 or more and no negative heat or hull floor, got "
					+ hardness + ", " + fuelMultiplier + ", " + heatHullPerSecond + ", " + hullFloor);
		}
	}
}
