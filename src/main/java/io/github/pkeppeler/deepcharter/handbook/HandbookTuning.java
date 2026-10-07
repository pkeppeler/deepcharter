package io.github.pkeppeler.deepcharter.handbook;

/**
 * Tunables for the handbook feature, read as {@code HandbookTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record HandbookTuning() {
	public static final HandbookTuning DEFAULT = new HandbookTuning();
}
