package io.github.pkeppeler.deepcharter.client.sound;

/**
 * Client entry point for the sound feature, called from DeepCharterClient. Issues: #57, #71, #866, #1450.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class SoundClientInit {
    private SoundClientInit() {}

    public static void init() {
        SoundClientRegistry.register();
        PodLoops.init();
        TerminalMusic.init();
        DreadBed.init();
        AmbienceTicker.init();
    }
}
