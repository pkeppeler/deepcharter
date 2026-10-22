package io.github.pkeppeler.deepcharter.outpost;

/**
 * Server entry point for the outpost feature, called from DeepCharter. Issues: #320.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class OutpostInit {
    private OutpostInit() {}

    public static void init() {
        OutpostRegistry.register();
    }
}
