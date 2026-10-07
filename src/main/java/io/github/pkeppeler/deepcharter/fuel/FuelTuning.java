package io.github.pkeppeler.deepcharter.fuel;

/**
 * Tunables for the fuel feature, read as {@code FuelTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record FuelTuning() {
	public static final FuelTuning DEFAULT = new FuelTuning();
}
