package io.github.pkeppeler.deepcharter.creature;

/**
 * Tunables for the creature feature, read as {@code CreatureTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record CreatureTuning() {
	public static final CreatureTuning DEFAULT = new CreatureTuning();
}
