package io.github.pkeppeler.deepcharter.ore;

/**
 * Tunables for the ore feature, read as {@code OreTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record OreTuning() {
	public static final OreTuning DEFAULT = new OreTuning();
}
