package io.github.pkeppeler.deepcharter.wreck;

/**
 * Tunables for the wreck feature, read as {@code WreckTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record WreckTuning() {
	public static final WreckTuning DEFAULT = new WreckTuning();
}
