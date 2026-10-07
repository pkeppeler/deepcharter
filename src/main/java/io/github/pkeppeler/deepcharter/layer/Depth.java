package io.github.pkeppeler.deepcharter.layer;

import java.util.OptionalInt;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/** Depth below sea level in blocks, read from the dimension-type registry so the client can compute it too. */
public final class Depth {
	private Depth() {
	}

	public static int of(Level level, int y) {
		Identifier type = level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier();
		return of(level.registryAccess(), type, y);
	}

	/** In a layer: its top depth plus the distance below its top. Elsewhere: sea level minus y. */
	public static int of(RegistryAccess registries, Identifier dimensionType, int y) {
		OptionalInt found = LayerChain.layerOf(dimensionType);
		if (found.isEmpty()) {
			return LayerTuning.DEFAULT.seaLevel() - y;
		}
		int layer = found.getAsInt();
		DimensionType type = registries.lookupOrThrow(Registries.DIMENSION_TYPE).getValueOrThrow(LayerChain.type(layer));
		return LayerChain.topDepth(registries, layer) + (type.minY() + type.height() - y);
	}

	public static int feet(int blocks) {
		return (int) Math.round(blocks * LayerTuning.DEFAULT.feetPerBlock());
	}
}
