package io.github.pkeppeler.deepcharter.repair;

/**
 * Tunables for the repair feature, read as {@code RepairTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record RepairTuning() {
	public static final RepairTuning DEFAULT = new RepairTuning();
}
