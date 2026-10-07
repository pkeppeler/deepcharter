package io.github.pkeppeler.deepcharter.layer;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The chain of layer dimensions, {@code deepcharter:layer_1}, {@code layer_2} and so on, each
 * with a dimension type of the same id. Layer thickness is read from the dimension-type
 * registry, which the server syncs to clients, so the client computes the same chain.
 */
public final class LayerChain {
	private static final String PREFIX = "layer_";

	private LayerChain() {
	}

	public static ResourceKey<Level> dimension(int layer) {
		return ResourceKey.create(Registries.DIMENSION, id(layer));
	}

	public static ResourceKey<DimensionType> type(int layer) {
		return ResourceKey.create(Registries.DIMENSION_TYPE, id(layer));
	}

	/** The layer a dimension type belongs to (1-based), or 0 if it is not a layer. */
	public static int indexOf(Identifier dimensionType) {
		if (!dimensionType.getNamespace().equals(DeepCharter.MOD_ID) || !dimensionType.getPath().startsWith(PREFIX)) {
			return 0;
		}
		try {
			return Math.max(0, Integer.parseInt(dimensionType.getPath().substring(PREFIX.length())));
		} catch (NumberFormatException e) {
			return 0;
		}
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

	/** Depth of the top of a layer, in blocks: the surface's floor depth plus the layers above it. */
	public static int topDepth(RegistryAccess registries, int layer) {
		Registry<DimensionType> types = registries.lookupOrThrow(Registries.DIMENSION_TYPE);
		int depth = LayerTuning.DEFAULT.firstLayerDepth();
		for (int above = 1; above < layer; above++) {
			depth += types.getValueOrThrow(type(above)).height();
		}
		return depth;
	}

	private static Identifier id(int layer) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, PREFIX + layer);
	}
}
