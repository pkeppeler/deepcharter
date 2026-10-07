package io.github.pkeppeler.deepcharter.layer;

/**
 * Tunables for the layer feature. Add one component per tunable and give it its value in
 * {@link #DEFAULT}; read it as {@code LayerTuning.DEFAULT.thing()}.
 *
 * @param seaLevel     the Y of depth 0 on the surface
 * @param feetPerBlock the altimeter's feet per block
 */
public record LayerTuning(int seaLevel, double feetPerBlock) {
	public static final LayerTuning DEFAULT = new LayerTuning(63, 3.28);
}
