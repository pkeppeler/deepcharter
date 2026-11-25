package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether an outpost's shell holds its depth's ratings on the suit's axes (#989, ADR 0076, SPEC section 11, ADR 0083): heat, pressure and
 * flooding ({@link #AXES}). An outpost whose shell has a block below the rating on one of them is uninhabitable: it is never destroyed, and
 * nothing here changes a block. The suit timer that keeps running inside, and the risk line of its terminals, read this and nothing else.
 * Quake is not judged: a shortfall there does not leak air, it is a structural risk.
 *
 * <p>The shell is the hall's floor, walls and ceiling ({@link OutpostBuilder}), whatever stands there: the plates the kit sets, the crust or
 * rock it leaves in the middle of the floor and the ceiling. A gap in it (air or a fluid) is not a block and is not judged: a gap is the
 * creature rule's exposure (#926), not a rating. What stands inside the shell is not judged either.
 */
public final class OutpostHabitability {
    /** The axes a shell is judged on: what leaks air. */
    public static final Set<RatingAxis> AXES =
            Collections.unmodifiableSet(EnumSet.of(RatingAxis.HEAT, RatingAxis.PRESSURE, RatingAxis.FLOODING));

    private OutpostHabitability() {}

    /**
     * What the shell is like.
     *
     * @param habitable   true when every block of the shell holds the depth's rating on {@link #AXES}, and the depth has ratings at all
     * @param shellBlocks how many blocks stand in the shell (a gap is not counted)
     * @param unrated     how many of them are below the rating on one of {@link #AXES}; with no ratings for the depth, all of them
     * @param shortOn     the axes of {@link #AXES} on which some block is below the rating
     */
    public record Status(boolean habitable, int shellBlocks, int unrated, Set<RatingAxis> shortOn) {}

    /** What the outpost's depth asks of its shell: the row at the hall's middle, empty where the depth has none. */
    public static Optional<Ratings> requiredAt(Level level, Outpost outpost) {
        return DepthRatings.requiredAt(level, outpost.centre().getY());
    }

    /**
     * The state of {@code outpost}'s shell as {@code level} has it, against a depth that asks {@code required} ({@link #requiredAt} for its own, empty for a
     * depth with no rated material). Reads blocks and changes none.
     */
    public static Status of(BlockGetter level, Outpost outpost, Optional<Ratings> required) {
        BlockPos centre = outpost.centre();
        int edge = OutpostBuilder.EDGE;
        int top = OutpostBuilder.HEIGHT + 1;
        Set<RatingAxis> shortOn = EnumSet.noneOf(RatingAxis.class);
        int blocks = 0;
        int unrated = 0;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-edge, 0, -edge), centre.offset(edge, top, edge))) {
            if (outpost.encloses(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            blocks++;
            if (required.isEmpty()) {
                unrated++;
                continue;
            }
            List<RatingAxis> leaks = BlockRatings.of(state).shortfalls(required.get()).stream()
                    .filter(AXES::contains)
                    .toList();
            if (!leaks.isEmpty()) {
                unrated++;
                shortOn.addAll(leaks);
            }
        }
        return new Status(required.isPresent() && unrated == 0, blocks, unrated, Set.copyOf(shortOn));
    }
}
