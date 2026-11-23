package io.github.pkeppeler.deepcharter.layer;

/** Gives the tests in other packages the server-stop reset of {@link CaveIns}, which firing the real event would run for every feature. */
public final class CaveInsProbe {
    private CaveInsProbe() {}

    public static void stopped() {
        CaveIns.stopped();
    }
}
