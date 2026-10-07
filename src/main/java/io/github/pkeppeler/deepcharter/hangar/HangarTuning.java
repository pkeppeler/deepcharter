package io.github.pkeppeler.deepcharter.hangar;

/**
 * Tunables for the hangar feature, read as {@code HangarTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record HangarTuning() {
	public static final HangarTuning DEFAULT = new HangarTuning();
}
