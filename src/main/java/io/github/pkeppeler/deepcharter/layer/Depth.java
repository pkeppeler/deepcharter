package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import java.util.Optional;
import net.minecraft.world.level.Level;

/** Depth below sea level in blocks, read from the dimension-type registry so the client can compute it too. */
public final class Depth {
    private Depth() {}

    public static int of(Level level, int y) {
        Optional<Band> band = LayerChain.layerAt(level, y);
        if (band.isEmpty()) {
            return LayerTuning.DEFAULT.seaLevel() - y;
        }
        Band layer = band.get();
        return LayerChain.topDepth(level.registryAccess(), layer.layer()) + (layer.minY() + layer.height() - y);
    }

    public static int feet(int blocks) {
        return (int) Math.round(blocks * LayerTuning.DEFAULT.feetPerBlock());
    }
}
