package io.github.pkeppeler.deepcharter.layer;

/** Depth below sea level in blocks. The client computes it too. */
public final class Depth {
    private Depth() {}

    /** The campaign world counts depth from sea level in every band (ADR 0059). */
    public static int of(int y) {
        return LayerTuning.DEFAULT.seaLevel() - y;
    }

    public static int feet(int blocks) {
        return (int) Math.round(blocks * LayerTuning.DEFAULT.feetPerBlock());
    }
}
