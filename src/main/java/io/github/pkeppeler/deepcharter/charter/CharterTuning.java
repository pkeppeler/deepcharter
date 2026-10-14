package io.github.pkeppeler.deepcharter.charter;

/**
 * Tunables for the charter feature, read as {@code CharterTuning.DEFAULT.thing()}.
 *
 * @param maxNameLength the longest charter name, in characters, after trimming
 * @param deepestPointPollTicks server ticks between checks of how deep each charter member is
 */
public record CharterTuning(int maxNameLength, int deepestPointPollTicks) {
    public static final CharterTuning DEFAULT = new CharterTuning(32, 20);

    public CharterTuning {
        if (maxNameLength < 1) {
            throw new IllegalArgumentException("maxNameLength must be at least 1, got " + maxNameLength);
        }
        if (deepestPointPollTicks < 1) {
            throw new IllegalArgumentException(
                    "deepestPointPollTicks must be at least 1, got " + deepestPointPollTicks);
        }
    }
}
