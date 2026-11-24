package io.github.pkeppeler.deepcharter.client.suit;

/**
 * Client entry point for the suit feature, called from DeepCharterClient. Issues: #993.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class SuitClientInit {
    private SuitClientInit() {}

    public static void init() {
        SuitGaugeHud.init();
    }
}
