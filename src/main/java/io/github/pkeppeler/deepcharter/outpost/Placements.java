package io.github.pkeppeler.deepcharter.outpost;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether a block would hold where it is placed (#989, ADR 0076, ADR 0072): the words of the placement readout, decided from the block, the depth
 * and a place of any other feature that shelters it, so a server GameTest can check them. The client draws them ({@code PlacementReadoutHud}).
 *
 * <p>A block holds when it reaches the depth's rating on every axis ({@link #holds}). Otherwise it is sheltered when a {@link ShelterCheck}
 * says its place is, and it will not hold unattended when none does. Nothing in this feature says a place is sheltered: that is the creature
 * rule's exposure (open space reachable from outside, a closed rated door counting as shell, #926), which adds its check from its own init.
 */
public final class Placements {
    /** The placement readout of the game: the creature feature adds its shelter check here. */
    public static final Placements SHARED = new Placements();

    /** What the readout says about a block in hand at the aimed spot. */
    public enum Verdict {
        HOLDS_HERE,
        SHELTERED,
        WILL_NOT_HOLD;

        public Component label() {
            return Component.translatable("readout.deepcharter.placement." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** What the readout shows.
     *
     * @param verdict the one-word state
     * @param reason  for a block that will not hold, why: the axes it falls short on, or that nothing is rated at this depth yet
     */
    public record Readout(Verdict verdict, Optional<Component> reason) {}

    /** Says whether a place is inside a closed, rated shell, so that an unrated block there is safe. */
    @FunctionalInterface
    public interface ShelterCheck {
        boolean sheltered(Level level, BlockPos pos);
    }

    private final List<ShelterCheck> shelters = new CopyOnWriteArrayList<>();

    /** Adds a shelter check: a place any check calls sheltered reads SHELTERED for a block that does not hold. */
    // test-seam: the hook #926 registers its exposure rule through from its own init; only a test calls it until that
    // issue lands
    public void addShelterCheck(ShelterCheck check) {
        shelters.add(check);
    }

    /** True when {@code state} placed at {@code pos} in {@code level} reaches the rating that depth asks on every axis. */
    public static boolean holds(Level level, BlockPos pos, BlockState state) {
        return DepthRatings.requiredAt(level, pos.getY())
                .map(required -> BlockRatings.of(state).meets(required))
                .orElse(false);
    }

    /** The readout for {@code state} placed at {@code pos} in {@code level}. */
    public Readout readout(Level level, BlockPos pos, BlockState state) {
        if (holds(level, pos, state)) {
            return new Readout(Verdict.HOLDS_HERE, Optional.empty());
        }
        Optional<Ratings> required = DepthRatings.requiredAt(level, pos.getY());
        if (required.isEmpty()) {
            return new Readout(
                    shelteredAt(level, pos) ? Verdict.SHELTERED : Verdict.WILL_NOT_HOLD,
                    Optional.of(Component.translatable("readout.deepcharter.placement.nothing_rated")));
        }
        List<RatingAxis> missing = BlockRatings.of(state).shortfalls(required.get());
        Component axes = missing.stream()
                .map(RatingAxis::label)
                .reduce((a, b) -> Component.empty().append(a).append(", ").append(b))
                .orElseThrow();
        return new Readout(
                shelteredAt(level, pos) ? Verdict.SHELTERED : Verdict.WILL_NOT_HOLD,
                Optional.of(Component.translatable("readout.deepcharter.placement.below_rating", axes)));
    }

    private boolean shelteredAt(Level level, BlockPos pos) {
        return shelters.stream().anyMatch(check -> check.sheltered(level, pos));
    }
}
