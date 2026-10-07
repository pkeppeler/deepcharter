package io.github.pkeppeler.deepcharter.charter;

/**
 * Tunables for the charter feature, read as {@code CharterTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record CharterTuning() {
	public static final CharterTuning DEFAULT = new CharterTuning();
}
