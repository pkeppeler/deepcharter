package io.github.pkeppeler.deepcharter.layer;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Depth below sea level, in blocks, computed from the dimension-type registry. Runs on the
 * client too.
 *
 * <p>In a layer: {@code topDepth(layer) + (minY + height - y)}, so the floor of one layer and the
 * top of the next read the same depth. Anywhere else: {@code seaLevel - y}.
 */
public final class Depth {
	private Depth() {
	}

	public static int of(Level level, int y) {
		Identifier type = level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier();
		return of(level.registryAccess(), type, y);
	}

	public static int of(RegistryAccess registries, Identifier dimensionType, int y) {
		int layer = LayerChain.indexOf(dimensionType);
		if (layer == 0) {
			return LayerTuning.DEFAULT.seaLevel() - y;
		}
		DimensionType type = registries.lookupOrThrow(Registries.DIMENSION_TYPE).getValueOrThrow(LayerChain.type(layer));
		return LayerChain.topDepth(registries, layer) + (type.minY() + type.height() - y);
	}

	public static int feet(int blocks) {
		return (int) Math.round(blocks * LayerTuning.DEFAULT.feetPerBlock());
	}
}
