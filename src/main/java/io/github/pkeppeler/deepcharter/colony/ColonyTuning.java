package io.github.pkeppeler.deepcharter.colony;

/**
 * Tunables for the colony feature, read as {@code ColonyTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record ColonyTuning() {
	public static final ColonyTuning DEFAULT = new ColonyTuning();
}
