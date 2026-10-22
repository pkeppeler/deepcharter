package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import java.util.List;

/**
 * The band table of the campaign world (ADR 0059): the one place the bands' Ys are written in Java. Every data file that names a band
 * edge is checked against it ({@code CampaignTerrainTest.theDataMatchesTheBandTable}). It lives in the test mod until the cutover
 * (#214) makes {@code LayerChain.bandAt} read the bands, which is the first production reader; then it moves to {@code src/main}.
 */
public final class CampaignBands {
    /** One band: {@code minY} is its lowest block and {@code height} the blocks it spans. */
    public record Band(String name, int minY, int height) {
        /** The highest block of the band. */
        public int maxY() {
            return minY + height - 1;
        }

        /** The first Y above the band. */
        public int topExclusive() {
            return minY + height;
        }
    }

    public static final Band SURFACE = new Band("surface", -64, 384);
    public static final Band LAYER_1 = new Band("layer 1", -256, 192);
    public static final Band LAYER_2 = new Band("layer 2", -512, 256);
    public static final Band UNBUILT = new Band("unbuilt depth", -2032, 1520);

    /** Top to bottom. */
    public static final List<Band> ALL = List.of(SURFACE, LAYER_1, LAYER_2, UNBUILT);

    /** The layers, top to bottom: index 0 is layer 1. */
    public static final List<Band> LAYERS = List.of(LAYER_1, LAYER_2);

    private CampaignBands() {}

    /** The band of layer {@code layer}, counted from 1. */
    public static Band layer(int layer) {
        return LAYERS.get(layer - 1);
    }

    /** Rows of crust at the floor of each layer. */
    public static int crustRows() {
        return LayerTuning.DEFAULT.crustThickness();
    }

    /** The lowest Y of the world. */
    public static int worldMinY() {
        return UNBUILT.minY();
    }

    /** The first Y above the world. */
    public static int worldTopExclusive() {
        return SURFACE.topExclusive();
    }
}
