package io.github.pkeppeler.deepcharter.sound.ambience;

import java.util.Locale;

/**
 * How worn a fragment is, which is how deep it is heard (tools/sounds/ambience.py makes each): faint and far in the first layer, worn in
 * the Old Workings, broken (slowed, filtered, stuttering) everywhere deeper. A sound event of a tier is {@code ambience.<tier>.<fragment>}.
 *
 * <p>{@link #HORN} is not a depth: it is the pool of fragments only a Company horn plays (#946), worn like {@link #WORN}. No layer's rate
 * names it, so the dark never plays one; {@link AmbienceScheduler} adds it to a horn's choices.
 */
public enum AmbienceTier {
    FAINT,
    WORN,
    BROKEN,
    HORN;

    /** The tier's name as the sound events and the pipeline spell it. */
    String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
