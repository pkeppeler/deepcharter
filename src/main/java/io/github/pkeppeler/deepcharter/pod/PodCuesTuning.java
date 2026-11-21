package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the pod hazard cues (#854).
 *
 * @param overloadRepeatTicks ticks between repeats of the overloaded-lift cue while jump stays held
 */
public record PodCuesTuning(int overloadRepeatTicks) {
    /** (A) 40 ticks, 2 s: a held key repeats about as often as the action bar line fades, a tap on a new press cues at once. */
    public static final PodCuesTuning DEFAULT = new PodCuesTuning(40);

    public PodCuesTuning {
        if (overloadRepeatTicks <= 0) {
            throw new IllegalArgumentException(
                    "the overload cue repeats after 1 or more ticks, got " + overloadRepeatTicks);
        }
    }
}
