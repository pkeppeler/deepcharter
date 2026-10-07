package io.github.pkeppeler.deepcharter.layer;

/**
 * Tunables for the layer feature. Add one component per tunable and give it its value in
 * {@link #DEFAULT}; read it as {@code LayerTuning.DEFAULT.thing()}.
 *
 * @param seaLevel        the Y of depth 0 on the surface
 * @param firstLayerDepth depth of the top of layer 1; equals the surface's depth at its own floor
 * @param feetPerBlock    the altimeter's feet per block
 * @param crustThickness  blocks of breach crust at each layer floor; the layer dimension JSON's
 *                        flat generator must stack the same number
 */
public record LayerTuning(int seaLevel, int firstLayerDepth, double feetPerBlock, int crustThickness) {
	public static final LayerTuning DEFAULT = new LayerTuning(63, 127, 3.28, 3);
}
