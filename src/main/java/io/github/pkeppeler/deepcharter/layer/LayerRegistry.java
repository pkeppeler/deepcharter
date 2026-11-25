package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.gen.AirFaceLiningFeature;
import io.github.pkeppeler.deepcharter.layer.gen.BandBiomeSource;
import io.github.pkeppeler.deepcharter.layer.gen.GrainedCrustPass;
import io.github.pkeppeler.deepcharter.layer.gen.InChunkOffsetPlacement;
import io.github.pkeppeler.deepcharter.layer.gen.LayerRowsPlacement;
import io.github.pkeppeler.deepcharter.layer.gen.ZoneBiomeSource;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.TicketType;

/** Registrations of the layer chain. The dimensions are data, so only the blocks, the biome sources and the generator's own
 * placement and feature register in code. */
public final class LayerRegistry {
    /**
     * The ticket that holds the arrival area of the next dimension (#1159, ADR 0089): it loads and simulates the chunks, keeps the
     * dimension active, and is not saved ({@code FLAG_PERSIST} unset), so a restart finds no ticket and the approach starts again. It lapses
     * {@link LayerTuning#seamTicketTimeout()} ticks after its last refresh. Vanilla's own types do not fit: {@code PORTAL} and
     * {@code ENDER_PEARL} time out at 300 and 40 ticks and are shared with portals and pearls.
     */
    public static final TicketType SEAM_ARRIVAL = Registry.register(
            BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "seam_arrival"),
            new TicketType(
                    LayerTuning.DEFAULT.seamTicketTimeout(),
                    TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

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
