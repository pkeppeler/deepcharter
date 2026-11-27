package io.github.pkeppeler.deepcharter.suit;

/**
 * Server entry point for the suit feature, called from DeepCharter. Issues: #993, #1064.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class SuitInit {
    private SuitInit() {}

    public static void init() {
        SuitRegistry.register();
        SuitTicker.init();
        OutpostRisks.init();
        SuitShop.init();
    }
}
