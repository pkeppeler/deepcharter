package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.Level;

/**
 * What each depth asks of a block (#989, ADR 0076): {@link RatingTuning#layers} for a built layer, nothing for the surface. A depth with
 * no entry has no rated material yet (SPEC: no outpost there can be made habitable), which {@link #requiredAt} says by being empty.
 */
public final class DepthRatings {
    private DepthRatings() {}

    /** What layer {@code layer} (1 is the first below the surface) asks, {@link Ratings#NONE} for the surface, empty for a layer with no ratings yet. */
    public static Optional<Ratings> forLayer(int layer) {
        if (layer == LayerChain.SURFACE) {
            return Optional.of(Ratings.NONE);
        }
        List<Ratings> layers = RatingTuning.DEFAULT.layers();
        return layer >= 1 && layer <= layers.size() ? Optional.of(layers.get(layer - 1)) : Optional.empty();
    }

    /**
     * What a block at height {@code y} of {@code level} must hold. Empty in the campaign world's unbuilt depth and in a layer with no
     * ratings yet: nothing is rated there. A level outside the chain (the Nether, a level whose dimension type a data pack gives inline) asks
     * nothing, so the readout drawn every tick never throws.
     */
    public static Optional<Ratings> requiredAt(Level level, int y) {
        if (level.dimensionTypeRegistration().unwrapKey().isEmpty()) {
            return Optional.of(Ratings.NONE);
        }
        Optional<Band> band = LayerChain.bandAt(level, y);
        if (band.isEmpty()) {
            return LayerChain.isCampaign(level) ? Optional.empty() : Optional.of(Ratings.NONE);
        }
        return forLayer(band.get().layer());
    }
}
