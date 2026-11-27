package io.github.pkeppeler.deepcharter.sound;

import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceRegistry;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographReadout;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographRegistry;

/**
 * Server entry point for the sound feature, called from DeepCharter. Issues: #57, #71, #864, #866, #1267.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class SoundInit {
    private SoundInit() {}

    public static void init() {
        SoundRegistry.register();
        PhonographRegistry.register();
        AmbienceRegistry.register();
        PhonographReadout.init();
        TerminalSounds.init();
        HymnMoments.init();
    }
}
