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

    private LayerChain() {}

    /**
     * A vertical span of the chain: {@code layer} is {@link #SURFACE} or the layer number, {@code minY} and {@code height} the blocks it
     * spans in its level. For now a band is a whole dimension.
     */
    public record Band(int layer, int minY, int height) {
        /** True for a layer, false for the surface. */
        public boolean isLayer() {
            return layer != SURFACE;
        }
    }

    /**
     * The band that {@code y} in {@code level} falls in; empty for a level that is not in the chain (the Nether, the End). Fails for a
     * dimension type given inline by a data pack, which has no key.
     */
    public static Optional<Band> bandAt(Level level, int y) {
        Identifier type =
                level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier();
        return indexOf(type).stream()
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
     * The band of layer {@code layer} as {@code generator} lays it out in a level: a band of a campaign world, or the whole level of a
     * layer dimension.
     */
    public static Band layerBand(ChunkGenerator generator, LevelHeightAccessor level, int layer) {
        if (generator.getBiomeSource() instanceof BandBiomeSource source) {
            BandBiomeSource.Layer span = source.layers().get(layer - 1);
            return new Band(layer, span.minY(), span.height());
        }
        return new Band(layer, level.getMinY(), level.getHeight());
    }

    /** {@link #bandAt} for a layer; empty on the surface too. */
    public static Optional<Band> layerAt(Level level, int y) {
        return bandAt(level, y).filter(Band::isLayer);
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

    /** Layer 1 starts at the overworld's floor; each later layer starts where the one above ends. */
    public static int topDepth(RegistryAccess registries, int layer) {
        Registry<DimensionType> types = registries.lookupOrThrow(Registries.DIMENSION_TYPE);
        int depth = LayerTuning.DEFAULT.seaLevel()
                - types.getValueOrThrow(BuiltinDimensionTypes.OVERWORLD).minY();
        for (int above = 1; above < layer; above++) {
            depth += types.getValueOrThrow(type(above)).height();
        }
        return depth;
    }

    private static Identifier id(int layer) {
        if (layer < 1) {
            throw new IllegalArgumentException("Layer numbers start at 1: " + layer);
        }
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, PREFIX + layer);
    }
}
