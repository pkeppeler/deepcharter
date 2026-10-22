package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
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

    /**
     * The dimension type of the campaign world's test dimension, where a layer is a Y band of one level ({@link CampaignBands}). The
     * shipped campaign type joins it at the cutover (#214).
     */
    private static final Identifier CAMPAIGN_TYPE =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "campaign_sample");

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
    }

    /** True for a level of the campaign world, where the layers are Y bands of one level and a crossing moves nothing. */
    public static boolean isCampaign(Level level) {
        return dimensionType(level).equals(CAMPAIGN_TYPE);
    }

    /**
     * The band that {@code y} in {@code level} falls in; empty for a level that is not in the chain (the Nether, the End), and for
     * unbuilt depth in the campaign world. Fails for a dimension type given inline by a data pack, which has no key.
     */
    public static Optional<Band> bandAt(Level level, int y) {
        Identifier type = dimensionType(level);
        if (type.equals(CAMPAIGN_TYPE)) {
            return CampaignBands.bandAt(y);
        }
        return indexOf(type).stream()
                .mapToObj(layer -> new Band(layer, level.getMinY(), level.getHeight()))
                .findFirst();
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
