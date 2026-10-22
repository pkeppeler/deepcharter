package io.github.pkeppeler.deepcharter.layer;

import java.util.List;
import java.util.Optional;

/**
 * The band table of the campaign world (ADR 0059): the one place the bands' Ys are written in Java. Every data file that names a band
 * edge is checked against it ({@code CampaignTerrainTest.theDataMatchesTheBandTable}). {@link LayerChain#bandAt} and
 * {@link BandCrossing} read it for a level of the campaign world.
 */
public final class CampaignBands {
    /** One band: {@code minY} is its lowest block and {@code height} the blocks it spans. */
    public record Band(String name, int minY, int height) {
        /** The highest block of the band. */
        public int maxY() {
            return topExclusive() - 1;
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
    public static final List<Band> LAYERS = ALL.subList(1, ALL.size() - 1);

    private CampaignBands() {}

    /** The band at chain position {@code layer}: {@link LayerChain#SURFACE}, or the layer counted from 1. */
    public static Band ofLayer(int layer) {
        return layer == LayerChain.SURFACE ? SURFACE : layer(layer);
    }

    /** The deepest layer the campaign world builds; below it is unbuilt depth. */
    public static int lastLayer() {
        return LAYERS.size();
    }

    /** The chain band that {@code y} falls in; empty in unbuilt depth, which is no layer yet, and outside the world. */
    public static Optional<LayerChain.Band> bandAt(int y) {
        for (int layer = LayerChain.SURFACE; layer <= lastLayer(); layer++) {
            Band band = ofLayer(layer);
            if (y >= band.minY() && y <= band.maxY()) {
                return Optional.of(new LayerChain.Band(layer, band.minY(), band.height()));
            }
        }
        return Optional.empty();
    }

    /** The band of layer {@code layer}, counted from 1. */
    public static Band layer(int layer) {
        return LAYERS.get(layer - 1);
    }

    /** Rows of crust at the floor of each layer. */
    public static int crustRows() {
        return LayerTuning.DEFAULT.crustThickness();
    }
}
