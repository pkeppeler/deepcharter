package io.github.pkeppeler.deepcharter.sound;

/** Gives the tests in other packages the server-stop reset of {@link HymnMoments}, which firing the real event would run for every feature. */
public final class HymnMomentsProbe {
    private HymnMomentsProbe() {}

    public static void stopped() {
        HymnMoments.stopped();
    }
}
