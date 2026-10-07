package io.github.pkeppeler.deepcharter.layer;

/**
 * Tunables for the layer feature. Add one component per tunable and give it its value in
 * {@link #DEFAULT}; read it as {@code LayerTuning.DEFAULT.thing()}.
 */
public record LayerTuning() {
	public static final LayerTuning DEFAULT = new LayerTuning();
}
