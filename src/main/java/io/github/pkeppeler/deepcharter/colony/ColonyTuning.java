package io.github.pkeppeler.deepcharter.colony;

/**
 * Tunables for the colony feature, read as {@code ColonyTuning.DEFAULT.thing()}.
 *
 * @param padSize       the flattened pad is this many blocks on each side, centred on the world spawn
 * @param clearHeight   the pad is cleared of terrain, trees and water this many blocks above its ground
 * @param fillDepth     a hollow under the pad is filled with dirt at most this many blocks below its ground
 * @param conduitRadius the Conduit's casing reaches this many blocks out from its centre column on each side
 * @param conduitStack  how many blocks the Conduit rises above the pad's ground in the overworld
 */
public record ColonyTuning(int padSize, int clearHeight, int fillDepth, int conduitRadius, int conduitStack) {
	public static final ColonyTuning DEFAULT = new ColonyTuning(64, 24, 48, 1, 10);
}
