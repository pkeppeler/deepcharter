package io.github.pkeppeler.deepcharter.client.charter;

import io.github.pkeppeler.deepcharter.charter.CharterView;
import java.util.Optional;

/** The charter the server last told this client about, or empty when the player is on none. Read and written on the client thread. */
public final class ClientCharter {
    private static Optional<CharterView> view = Optional.empty();

    private ClientCharter() {}

    public static Optional<CharterView> view() {
        return view;
    }

    static void set(Optional<CharterView> newView) {
        view = newView;
    }
}
