package io.github.pkeppeler.deepcharter.colony;

/**
 * Server entry point for the colony feature, called from DeepCharter. Issues: #64.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class ColonyInit {
    private ColonyInit() {}

    public static void init() {
        ColonyRegistry.register();
        WorldFormat.init();
        WorldFormatGate.init();
        ColonyBuilder.init();
        Conduit.init();
        FounderStatue.init();
        ColonyDisplays.init();
        ColonyPhonograph.init();
        ColonyRations.init();
        ColonyReadout.init();
    }
}
