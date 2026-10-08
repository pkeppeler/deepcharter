package io.github.pkeppeler.deepcharter.layer;

/**
 * Tunables for the layer feature. Add one component per tunable and give it its value in
 * {@link #DEFAULT}; read it as {@code LayerTuning.DEFAULT.thing()}.
 *
 * @param seaLevel       the Y of depth 0 on the surface
 * @param feetPerBlock   the altimeter's feet per block
 * @param crustThickness blocks of breach crust at the floor of each layer; the layer JSONs stack this many
 * @param pocketRadius   a crossing carves a pocket this many blocks out from the arrival column on each side
 * @param pocketHeight   blocks of air in that pocket; an entity arrives at its bottom, so this is also how far
 *                       from the crossing line it starts
 * @param lavaHullPerSecond hull points a pod loses each second while it is in or touching lava, before a radiator
 */
public record LayerTuning(int seaLevel, double feetPerBlock, int crustThickness, int pocketRadius, int pocketHeight,
		float lavaHullPerSecond) {
	public static final LayerTuning DEFAULT = new LayerTuning(63, 3.28, 3, 2, 4, 10f);
}
