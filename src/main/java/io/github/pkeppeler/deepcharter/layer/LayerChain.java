package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.gen.BandBiomeSource;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * The chain of dimensions: the overworld is layer 0, the surface, and {@code deepcharter:layer_<n>} for
 * n from 1 is each deeper layer, with a dimension type of the same id.
 */
public final class LayerChain {
    /** The overworld's place in the chain. */
    public static final int SURFACE = 0;

    private static final String PREFIX = "layer_";

    /** The dimension type of the campaign world (#214): the overworld's own, which is tall, with a band for the surface and for each layer. */
    private static final Identifier CAMPAIGN_TYPE = BuiltinDimensionTypes.OVERWORLD.identifier();

    private LayerChain() {}

    /**
     * A vertical span of the chain: {@code layer} is {@link #SURFACE} or the layer number, {@code minY} and {@code height} the blocks it
     * spans in its level. A band is a whole dimension, except in the campaign world, where it is a span of one level.
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
     * unbuilt depth; the overworld of any other type is one surface band, told by {@link Level#OVERWORLD} and not by its type; a layer
     * dimension is one band, whatever {@code y}; empty for a level that is not in the chain (the Nether, the End). Fails for a dimension type
     * given inline by a data pack, which has no key, unless the level is the overworld or a campaign level.
     */
    public static Optional<Band> bandAt(Level level, int y) {
        if (isCampaign(level)) {
            return CampaignBands.bandAt(y);
        }
        if (level.dimension().equals(Level.OVERWORLD)) {
            return Optional.of(new Band(SURFACE, level.getMinY(), level.getHeight()));
        }
        return indexOf(dimensionType(level)).stream()
                .mapToObj(layer -> new Band(layer, level.getMinY(), level.getHeight()))
                .findFirst();
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
     * The {@link #campaignLayers} inside {@code level}'s height: every one in a campaign world, and only its own in a level of the
     * campaign generator cut to one band's rows (the cost test's band windows, ADR 0086), where another band would lie outside it.
     */
    public static List<Band> campaignLayersIn(ServerLevel level) {
        return campaignLayers(level).stream()
                .filter(band -> !level.isOutsideBuildHeight(band.minY()) && !level.isOutsideBuildHeight(band.maxY()))
                .toList();
    }

    /**
     * The band of layer {@code layer} as {@code generator} lays it out in a level: a band of a campaign world, or the whole level of a
     * layer dimension. {@code feature} names the caller, for the failure when a campaign world has no such layer.
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

    /** The loaded level of layer {@code layer} of the chain; fails when the registry holds the layer but its level is not loaded. */
    public static ServerLevel levelOf(MinecraftServer server, int layer) {
        ServerLevel level = server.getLevel(dimension(layer));
        if (level == null) {
            throw new IllegalStateException("Layer " + layer + " is in the registry but its level is not loaded");
        }
        return level;
    }

    public static ResourceKey<Level> dimension(int layer) {
        return layer == SURFACE ? Level.OVERWORLD : ResourceKey.create(Registries.DIMENSION, id(layer));
    }

    public static ResourceKey<DimensionType> type(int layer) {
        return layer == SURFACE
                ? BuiltinDimensionTypes.OVERWORLD
                : ResourceKey.create(Registries.DIMENSION_TYPE, id(layer));
    }

    /**
     * Where a dimension type sits in the chain: {@link #SURFACE} for the overworld, the layer number for a
     * layer, empty for any other type (the Nether and the End are not in the chain).
     */
    public static OptionalInt indexOf(Identifier dimensionType) {
        if (dimensionType.equals(BuiltinDimensionTypes.OVERWORLD.identifier())) {
            return OptionalInt.of(SURFACE);
        }
        return layerOf(dimensionType);
    }

    /**
     * The layer (1-based) a dimension type belongs to; empty for any non-layer type: the surface, the
     * Nether or the End. A {@code deepcharter:layer_*} id that is not a positive number is a bug.
     */
    public static OptionalInt layerOf(Identifier dimensionType) {
        if (!dimensionType.getNamespace().equals(DeepCharter.MOD_ID)
                || !dimensionType.getPath().startsWith(PREFIX)) {
            return OptionalInt.empty();
        }
        int layer;
        try {
            layer = Integer.parseInt(dimensionType.getPath().substring(PREFIX.length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed layer dimension type: " + dimensionType, e);
        }
        if (layer < 1) {
            throw new IllegalArgumentException("Layer numbers start at 1: " + dimensionType);
        }
        return OptionalInt.of(layer);
    }

    /** How many layers the registry holds, counting from layer 1 without gaps. */
    public static int count(RegistryAccess registries) {
        Registry<DimensionType> types = registries.lookupOrThrow(Registries.DIMENSION_TYPE);
        int count = 0;
        while (types.containsKey(type(count + 1))) {
            count++;
        }
        return count;
    }

    /** How many layers the chain holds as {@code level} lays it out: the band table's for a campaign world, the registry's otherwise. */
    public static int layerCount(Level level) {
        return isCampaign(level) ? CampaignBands.lastLayer() : count(level.registryAccess());
    }

    /**
     * The depth in blocks below sea level where layer {@code layer} starts, as {@code level} lays the chain out: the same numbers
     * ({@link #topDepth(RegistryAccess, int)}) for the layer dimensions and the campaign world's bands. The layer after the last starts where
     * the last ends.
     */
    public static int topDepth(Level level, int layer) {
        if (!isCampaign(level)) {
            return topDepth(level.registryAccess(), layer);
        }
        int last = CampaignBands.lastLayer();
        if (layer < 1 || layer > last + 1) {
            throw new IllegalArgumentException("The campaign world has layers 1 to " + last + ", not " + layer);
        }
        int top = layer <= last
                ? CampaignBands.layer(layer).topExclusive()
                : CampaignBands.layer(last).minY();
        return LayerTuning.DEFAULT.seaLevel() - top;
    }

    /** The depth in blocks below sea level of the lowest block of the last layer of the chain, as {@code level} lays it out. */
    public static int deepestDepth(Level level) {
        if (isCampaign(level)) {
            return LayerTuning.DEFAULT.seaLevel()
                    - CampaignBands.layer(CampaignBands.lastLayer()).minY();
        }
        Registry<DimensionType> types = level.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        int last = count(level.registryAccess());
        return topDepth(level.registryAccess(), last)
                + types.getValueOrThrow(type(last)).height();
    }

    /**
     * Layer 1 starts at the floor of the surface band (the overworld's floor, before the overworld became the campaign world); each later
     * layer starts where the one above ends.
     */
    public static int topDepth(RegistryAccess registries, int layer) {
        Registry<DimensionType> types = registries.lookupOrThrow(Registries.DIMENSION_TYPE);
        int depth = LayerTuning.DEFAULT.seaLevel() - CampaignBands.SURFACE.minY();
        for (int above = 1; above < layer; above++) {
            depth += types.getValueOrThrow(type(above)).height();
        }
        return depth;
    }

    private static Identifier dimensionType(Level level) {
        return level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier();
    }

    private static Identifier id(int layer) {
        if (layer < 1) {
            throw new IllegalArgumentException("Layer numbers start at 1: " + layer);
        }
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, PREFIX + layer);
    }
}
