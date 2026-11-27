package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Keeps the transmission panel off the screen of every client test that is about something else. Founding a charter sends T01, and
 * standing in a layer's zones sends more: each is a panel over the middle of the view, which a screenshot or a pixel check would catch.
 * The overlay is emptied on both ends of every client tick, so nothing it is sent is ever drawn.
 *
 * <p>A test about transmissions, and every evidence scenario (which records the game as a player sees it), wraps itself in
 * {@link #shown()}.
 */
public final class QuietTransmissions implements ClientModInitializer {
    private static volatile boolean shown;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> quiet());
        ClientTickEvents.END_CLIENT_TICK.register(client -> quiet());
    }

    private static void quiet() {
        if (!shown) {
            TransmissionOverlay.reset();
        }
    }

    /** Lets transmissions reach the screen until the returned handle is closed. */
    public static Shown shown() {
        shown = true;
        return new Shown();
    }

    /** While open, transmissions are drawn. */
    public static final class Shown implements AutoCloseable {
        private Shown() {}

        @Override
        public void close() {
            shown = false;
        }
    }
}
