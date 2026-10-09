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
 * @param edgeMargin    the land outside each edge of the pad is graded to the pad over at least this many blocks
 * @param edgeMarginCap a side's margin grows with the height gap it grades, up to this many blocks; past it the natural relief resumes as a slope steeper than 1
 * @param edgeWobble    the edge of the flat ground wanders by up to this many blocks, so no straight line shows from the air; less where the gap is big
 * @param edgeLattice   the wander changes over about this many blocks
 * @param rimWidth      the pad's outer rim, in blocks, where the packed regolith and rock of the layers show through
 * @param scatterPercent the share of columns by the pad's edge, in percent, that show rock
 * @param scatterPackedFactor packed regolith shows on this many times as many columns as rock
 */
public record ColonyTuning(int padSize, int clearHeight, int fillDepth, int conduitRadius, int conduitStack,
		int searchStepChunks, int searchRings, int freshWorldTicks, int edgeMargin, int edgeMarginCap, int edgeWobble, int edgeLattice, int rimWidth, int scatterPercent,
		int scatterPackedFactor) {
	public static final ColonyTuning DEFAULT = new ColonyTuning(80, 36, 48, 1, 10, 4, 8, 2400, 32, 64, 4, 12, 3, 6, 3);
}
