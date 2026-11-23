package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.layer.gen.BandBiomeSource;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

/**
 * The chain of layers: 0 is the surface and n from 1 is each deeper layer. All of them are Y bands of the campaign world, the overworld
 * (ADR 0059): there is no dimension for a layer and a crossing moves nobody (ADR 0061).
 */
public final class LayerChain {
    /** The overworld's place in the chain. */
    public static final int SURFACE = 0;

    /** The dimension type of the campaign world (#214): the overworld's own, which is tall, with a band for the surface and for each layer. */
    private static final Identifier CAMPAIGN_TYPE = BuiltinDimensionTypes.OVERWORLD.identifier();

    private LayerChain() {}

    /**
     * A vertical span of the chain: {@code layer} is {@link #SURFACE} or the layer number, {@code minY} and {@code height} the blocks it
     * spans in its level.
     */
    public record Band(int layer, int minY, int height) {
        /** True for a layer, false for the surface. */
        public boolean isLayer() {
            return layer != SURFACE;
        }

        /** The highest block of the band. */
        public int maxY() {
            return minY + height - 1;
        }
    }

    /**
     * True for a level of the campaign world, where the layers are Y bands of one level and a crossing moves nothing. False for a level
     * whose dimension type is given inline, which has no key.
     */
    public static boolean isCampaign(Level level) {
        return level.dimensionTypeRegistration().is(CAMPAIGN_TYPE);
    }

    /**
     * The band that {@code y} in {@code level} falls in: in the campaign world the band of {@link CampaignBands} at {@code y}, empty in
     * unbuilt depth; the overworld of any other type (the test pack's flat one) is one surface band, told by {@link Level#OVERWORLD} and
     * not by its type; empty for any other level (the Nether, the End).
     */
    public static Optional<Band> bandAt(Level level, int y) {
        if (isCampaign(level)) {
            return CampaignBands.bandAt(y);
        }
        if (level.dimension().equals(Level.OVERWORLD)) {
            return Optional.of(new Band(SURFACE, level.getMinY(), level.getHeight()));
        }
        return Optional.empty();
    }

    /**
     * The layers of a campaign world (#743), whose generator's biome source is a {@link BandBiomeSource}: layer 1 first, each as a band of
     * its one level. Empty for any other level.
     */
    public static List<Band> campaignLayers(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator().getBiomeSource() instanceof BandBiomeSource source)) {
            return List.of();
        }
        return IntStream.range(0, source.layers().size())
                .mapToObj(index -> {
                    BandBiomeSource.Layer layer = source.layers().get(index);
                    return new Band(index + 1, layer.minY(), layer.height());
                })
                .toList();
    }

    /**
     * The band of layer {@code layer} as {@code generator} lays it out in a level: a band of a campaign world, or the whole level for
     * any other generator (the test pack's one-layer baselines). {@code feature} names the caller, for the failure when a campaign world has no such layer.
     */
    public static Band layerBand(ChunkGenerator generator, LevelHeightAccessor level, int layer, String feature) {
        if (generator.getBiomeSource() instanceof BandBiomeSource source) {
            if (layer > source.layers().size()) {
                throw new IllegalArgumentException(feature + " names layer " + layer + ", but this campaign world has "
                        + source.layers().size() + " layers");
            }
            BandBiomeSource.Layer span = source.layers().get(layer - 1);
            return new Band(layer, span.minY(), span.height());
        }
        return new Band(layer, level.getMinY(), level.getHeight());
    }

    /** {@link #bandAt} for a layer; empty on the surface too. */
    public static Optional<Band> layerAt(Level level, int y) {
        return bandAt(level, y).filter(Band::isLayer);
    }

    /** How many layers the campaign world builds. */
    public static int layerCount() {
        return CampaignBands.lastLayer();
    }

    /**
     * The depth in blocks below sea level where layer {@code layer} starts. The layer after the last starts where the last ends.
     */
    public static int topDepth(int layer) {
        int last = CampaignBands.lastLayer();
        if (layer < 1 || layer > last + 1) {
            throw new IllegalArgumentException("The campaign world has layers 1 to " + last + ", not " + layer);
        }
        int top = layer <= last
                ? CampaignBands.layer(layer).topExclusive()
                : CampaignBands.layer(last).minY();
        return LayerTuning.DEFAULT.seaLevel() - top;
    }

    /** The depth in blocks below sea level of the lowest block of the last layer. */
    public static int deepestDepth() {
        return LayerTuning.DEFAULT.seaLevel()
                - CampaignBands.layer(CampaignBands.lastLayer()).minY();
    }
}
