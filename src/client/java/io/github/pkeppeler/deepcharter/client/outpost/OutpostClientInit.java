package io.github.pkeppeler.deepcharter.client.outpost;

/**
 * Client entry point for the outpost feature, called from DeepCharterClient. Issues: #989, #1004.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class OutpostClientInit {
    private OutpostClientInit() {}

    public static void init() {
        OutpostClientRegistry.register();
        PlacementReadoutHud.init();
    }
}
