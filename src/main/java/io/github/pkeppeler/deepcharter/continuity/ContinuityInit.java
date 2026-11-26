package io.github.pkeppeler.deepcharter.continuity;

/**
 * Server entry point for the Continuity Event, a person's death as the Company records it (#1337, ADR 0100), called from DeepCharter.
 * The death itself is vanilla's: what drops and where the person wakes do not change (SPEC section 12). This feature counts it, files it
 * in chat, tells the dying client what to draw, and puts out the person's lamp for everyone else. Its hooks into {@code ServerPlayer.die}
 * and {@code Player.getDeathSound} are mixins in {@code continuity/mixin}.
 */
public final class ContinuityInit {
    private ContinuityInit() {}

    public static void init() {
        ContinuityRegistry.register();
    }
}
