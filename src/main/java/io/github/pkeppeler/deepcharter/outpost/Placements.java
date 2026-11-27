package io.github.pkeppeler.deepcharter.outpost;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether a block would hold where it is placed (#989, ADR 0076, ADR 0072): the words of the placement readout, decided from the block and the depth
 * so a server GameTest can check them. The client draws them ({@code PlacementReadoutHud}).
 *
 * <p>The readout speaks only where a depth asks for something: not on the surface, and for now not in layers 1 and 2.
 */
public final class Placements {
    /** What the readout says about a block in hand at the aimed spot. */
    public enum Verdict {
        HOLDS_HERE,
        WILL_NOT_HOLD;

        public Component label() {
            return Component.translatable("readout.deepcharter.placement." + name().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * What the readout shows.
     *
     * @param verdict the one-word state
     * @param reason  for a block that will not hold, why: the axes it falls short on, or that nothing is rated at this depth yet
     */
    public record Readout(Verdict verdict, Optional<Component> reason) {}

    private Placements() {}

    /** The readout for {@code state} placed at {@code pos} in {@code level}: empty where the depth asks nothing. */
    public static Optional<Readout> readout(Level level, BlockPos pos, BlockState state) {
        return readout(DepthRatings.requiredAt(level, pos.getY()), state);
    }

    /** As {@link #readout(Level, BlockPos, BlockState)} for a depth that asks {@code required}, empty for a depth with no rated material. */
    public static Optional<Readout> readout(Optional<Ratings> required, BlockState state) {
        if (required.isPresent() && required.get().equals(Ratings.NONE)) {
            return Optional.empty();
        }
        if (required.isEmpty()) {
            return Optional.of(new Readout(
                    Verdict.WILL_NOT_HOLD,
                    Optional.of(Component.translatable("readout.deepcharter.placement.nothing_rated"))));
        }
        Ratings held = BlockRatings.of(state);
        if (held.meets(required.get())) {
            return Optional.of(new Readout(Verdict.HOLDS_HERE, Optional.empty()));
        }
        List<RatingAxis> missing = held.shortfalls(required.get());
        Component axes = RatingAxis.list(missing);
        return Optional.of(new Readout(
                Verdict.WILL_NOT_HOLD,
                Optional.of(Component.translatable("readout.deepcharter.placement.short_on", axes))));
    }
}
