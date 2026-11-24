package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.layer.gen.AirFaceLiningFeature;
import io.github.pkeppeler.deepcharter.layer.gen.BandBiomeSource;
import io.github.pkeppeler.deepcharter.layer.gen.GrainedCrustPass;
import io.github.pkeppeler.deepcharter.layer.gen.InChunkOffsetPlacement;
import io.github.pkeppeler.deepcharter.layer.gen.LayerRowsPlacement;
import io.github.pkeppeler.deepcharter.layer.gen.ZoneBiomeSource;

/** Registrations of the layer chain. The dimensions are data, so only the blocks, the biome sources and the generator's own
 * placement and feature register in code. */
public final class LayerRegistry {
    private LayerRegistry() {}

    public static void register() {
        LayerBlocks.register();
        ZoneBiomeSource.register();
        BandBiomeSource.register();
        GrainedCrustPass.register();
        LayerRowsPlacement.register();
        InChunkOffsetPlacement.register();
        AirFaceLiningFeature.register();
    }
}
