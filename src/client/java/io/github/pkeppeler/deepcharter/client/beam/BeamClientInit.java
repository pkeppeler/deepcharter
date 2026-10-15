package io.github.pkeppeler.deepcharter.client.beam;

/**
 * Client entry point for the fake light beams (#248), called from DeepCharterClient. The pod's headlamp beam is drawn by the pod's own
 * renderer ({@link PodBeams}); this starts the floodlights'.
 */
public final class BeamClientInit {
    private BeamClientInit() {}

    public static void init() {
        FloodlightBeams.init();
    }
}
