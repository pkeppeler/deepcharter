package io.github.pkeppeler.deepcharter.surface;

/**
 * Tunables for the surface feature, read as {@code SurfaceTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record SurfaceTuning() {
	public static final SurfaceTuning DEFAULT = new SurfaceTuning();
}
