package io.github.pkeppeler.deepcharter.layer;

import java.util.OptionalInt;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;

import io.github.pkeppeler.deepcharter.DeepCharter;

/** The layer dimensions {@code deepcharter:layer_<n>}, each with a dimension type of the same id. */
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

	/**
	 * The layer (1-based) a dimension type belongs to; empty for any non-layer type, which is the
	 * surface. A {@code deepcharter:layer_*} id that is not a positive number is a bug.
	 */
	public static OptionalInt layerOf(Identifier dimensionType) {
		if (!dimensionType.getNamespace().equals(DeepCharter.MOD_ID) || !dimensionType.getPath().startsWith(PREFIX)) {
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
		int depth = LayerTuning.DEFAULT.seaLevel() - types.getValueOrThrow(BuiltinDimensionTypes.OVERWORLD).minY();
		for (int above = 1; above < layer; above++) {
			depth += types.getValueOrThrow(type(above)).height();
		}
		return depth;
	}

	private static Identifier id(int layer) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, PREFIX + layer);
	}
}
