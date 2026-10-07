package io.github.pkeppeler.deepcharter.sound;

/**
 * Tunables for the sound feature, read as {@code SoundTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record SoundTuning() {
	public static final SoundTuning DEFAULT = new SoundTuning();
}
