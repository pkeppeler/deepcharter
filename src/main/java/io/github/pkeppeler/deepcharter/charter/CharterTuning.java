package io.github.pkeppeler.deepcharter.charter;

/**
 * Tunables for the charter feature, read as {@code CharterTuning.DEFAULT.thing()}.
 *
 * @param maxNameLength the longest charter name, in characters, after trimming
 */
public record CharterTuning(int maxNameLength) {
	public static final CharterTuning DEFAULT = new CharterTuning(32);

	public CharterTuning {
		if (maxNameLength < 1) {
			throw new IllegalArgumentException("maxNameLength must be at least 1, got " + maxNameLength);
		}
	}
}
