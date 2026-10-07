package io.github.pkeppeler.deepcharter.ore;

/**
 * Tunables for the ore feature, read as {@code OreTuning.DEFAULT.thing()}.
 *
 * @param massScale        pod units of mass for each unit of the original's ore mass: the original's pod outweighs
 *                         its ore by far, but here an engine lifts 100, so a full bay of deep ore must be too heavy
 * @param slowdownPerMass  fraction of walking speed lost for each unit of ore mass carried
 * @param maxSlowdown      the most walking speed that ore can take away; a loaded player still moves
 * @param gasDamagePerFoot hull points a gas blast costs for each foot of depth, at radiator factor 1. The original
 *                         charges (depth - 3000 ft) / 15, which here would be nothing in layer 1 and far more than
 *                         the 100-point hull below it; a flat 1/20 gives about 20 points at the top of Deep Claim
 * @param stockRadiator    the radiator factor of a pod with no radiator part (1; a better radiator is below 1).
 *                         A {@code PodStats} radiator stat replaces it where {@code GasHazard} applies damage
 * @param blastRadius      a gas blast clears the blocks within this many of the pocket on each axis, so 1 is 3 x 3 x 3
 */
public record OreTuning(float massScale, double slowdownPerMass, double maxSlowdown, float gasDamagePerFoot,
		float stockRadiator, int blastRadius) {
	public static final OreTuning DEFAULT = new OreTuning(5f, 0.02, 0.8, 0.05f, 1f, 1);
}
