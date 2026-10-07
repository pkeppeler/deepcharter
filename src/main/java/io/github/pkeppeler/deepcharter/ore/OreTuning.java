package io.github.pkeppeler.deepcharter.ore;

/**
 * Tunables for the ore feature, read as {@code OreTuning.DEFAULT.thing()}.
 *
 * @param massScale        pod units of mass for each unit of the original's ore mass: the original's pod outweighs
 *                         its ore by far, but here an engine lifts 100, so a full bay of deep ore must be too heavy
 * @param slowdownPerMass  fraction of walking speed lost for each unit of ore mass carried
 * @param maxSlowdown      the most walking speed that ore can take away; a loaded player still moves
 */
public record OreTuning(float massScale, double slowdownPerMass, double maxSlowdown) {
	public static final OreTuning DEFAULT = new OreTuning(5f, 0.02, 0.8);
}
