package io.github.pkeppeler.deepcharter.transmission;

/**
 * Tunables for the transmission feature, read as {@code TransmissionTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record TransmissionTuning() {
	public static final TransmissionTuning DEFAULT = new TransmissionTuning();
}
