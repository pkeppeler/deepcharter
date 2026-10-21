package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/** Depth below sea level in blocks, read from the dimension-type registry so the client can compute it too. */
public final class Depth {
    private Depth() {}

    public static int of(Level level, int y) {
        Optional<Band> band = LayerChain.bandAt(level, y).filter(Band::isLayer);
        if (band.isEmpty()) {
            return LayerTuning.DEFAULT.seaLevel() - y;
        }
        Band layer = band.get();
        return LayerChain.topDepth(level.registryAccess(), layer.layer()) + (layer.minY() + layer.height() - y);
    }

    /** In a layer: its top depth plus the distance below its top. Elsewhere: sea level minus y. */
    // test-seam: LayerDimensionsTest pins the registry-only path; the cutover (#214) deletes it with the dimension-type
    // readers
    public static int of(RegistryAccess registries, Identifier dimensionType, int y) {
        OptionalInt found = LayerChain.indexOf(dimensionType);
        if (found.isEmpty() || found.getAsInt() == LayerChain.SURFACE) {
            return LayerTuning.DEFAULT.seaLevel() - y;
        }
        int layer = found.getAsInt();
        DimensionType type =
                registries.lookupOrThrow(Registries.DIMENSION_TYPE).getValueOrThrow(LayerChain.type(layer));
        return LayerChain.topDepth(registries, layer) + (type.minY() + type.height() - y);
    }

    public static int feet(int blocks) {
        return (int) Math.round(blocks * LayerTuning.DEFAULT.feetPerBlock());
    }
}
