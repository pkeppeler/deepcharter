package io.github.pkeppeler.deepcharter.sound;

import net.minecraft.server.level.ServerPlayer;

/**
 * Gives the tests in other packages the server-stop reset of {@link HymnMoments}, which firing the real event would run for every
 * feature, and the one-player reset of a disconnect, which a test that keeps its player cannot fire.
 */
public final class HymnMomentsProbe {
    private HymnMomentsProbe() {}

    /** As a disconnect does: {@code player} is no longer hearing a hymn, so the next moment plays to them. */
    public static void forget(ServerPlayer player) {
        HymnMoments.forget(player.getUUID());
    }

    public static void stopped() {
        HymnMoments.stopped();
    }
}
