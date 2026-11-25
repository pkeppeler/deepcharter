package io.github.pkeppeler.deepcharter.sound.ambience;

import java.util.Locale;

/**
 * How worn a fragment is, which is how deep it is heard (tools/sounds/ambience.py makes each): faint and far in the first layer, worn in
 * the Old Workings, broken (slowed, filtered, stuttering) everywhere deeper. A sound event of a tier is {@code ambience.<tier>.<fragment>}.
 */
public enum AmbienceTier {
    FAINT,
    WORN,
    BROKEN;

    /** The tier's name as the sound events and the pipeline spell it. */
    String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
