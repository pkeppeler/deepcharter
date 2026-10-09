package io.github.pkeppeler.deepcharter.colony;

/**
 * Tunables for the colony feature, read as {@code ColonyTuning.DEFAULT.thing()}.
 *
 * @param padSize       the flattened pad is this many blocks on each side, centred on the world spawn
 * @param clearHeight   the pad is cleared of terrain, trees and water this many blocks above its ground
 * @param fillDepth     a hollow under the pad is filled with dirt at most this many blocks below its ground
 * @param conduitRadius the Conduit's casing reaches this many blocks out from its centre column on each side
 * @param conduitStack  how many blocks the Conduit rises above the pad's ground in the overworld
 * @param searchStepChunks a spawn in water makes the build look for dry ground in steps of this many chunks
 * @param searchRings   the search goes this many steps out from the spawn
 * @param freshWorldTicks building in a world that has run longer than this logs a warning: it is an existing world
 * @param edgeMargin    the terrain is graded into the pad over this many blocks outside each edge
 * @param edgeRamp      the grade reaches the natural ground at this distance from the pad, less the wobble of the edge
 * @param edgeWobble    the edge of the flat ground wanders by up to this many blocks, so no straight line shows from the air
 */
public record ColonyTuning(int padSize, int clearHeight, int fillDepth, int conduitRadius, int conduitStack,
		int searchStepChunks, int searchRings, int freshWorldTicks, int edgeMargin, int edgeRamp, int edgeWobble) {
	public static final ColonyTuning DEFAULT = new ColonyTuning(80, 36, 48, 1, 10, 4, 8, 2400, 32, 28, 4);
}
