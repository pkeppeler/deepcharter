package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether an outpost's shell holds its depth's ratings (#989, ADR 0076, SPEC section 11). An outpost whose shell has a block below the rating
 * on some axis is uninhabitable: it is never destroyed, and nothing here changes a block. The heat that leaks in, the water that seeps in and the
 * suit timer that keeps running are effects of that state, and none of them exists yet (ADR 0076 names the follow-ups): they read this and
 * nothing else.
 *
 * <p>The shell is the hall's floor, walls and ceiling ({@link OutpostBuilder}), whatever stands there: the plates the kit sets, the crust or
 * rock it leaves in the middle of the floor and the ceiling. A gap in it (air or a fluid) is not a block and is not judged: a gap is the
 * creature rule's exposure (#926), not a rating. What stands inside the shell is not judged either.
 */
public final class OutpostHabitability {
    private OutpostHabitability() {}

    /**
     * What the shell is like.
     *
     * @param habitable   true when every block of the shell holds the layer's rating, and the layer has ratings at all
     * @param shellBlocks how many blocks stand in the shell (a gap is not counted)
     * @param unrated     how many of them are below the rating on some axis; with no ratings for the layer, all of them
     * @param shortOn    the axes on which some block is below the rating
     */
    public record Status(boolean habitable, int shellBlocks, int unrated, Set<RatingAxis> shortOn) {}

    /** The state of {@code outpost}'s shell as {@code level} has it. Reads blocks and changes none. */
    // test-seam: the status the outpost's heat, seep and suit-timer effects and #926's terminal risk line read; only a
    // test calls it until they are built
    public static Status of(BlockGetter level, Outpost outpost) {
        Optional<Ratings> required = DepthRatings.forLayer(outpost.layer());
        BlockPos centre = outpost.centre();
        int edge = OutpostBuilder.EDGE;
        int top = OutpostBuilder.HEIGHT + 1;
        Set<RatingAxis> shortOn = EnumSet.noneOf(RatingAxis.class);
        int blocks = 0;
        int unrated = 0;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-edge, 0, -edge), centre.offset(edge, top, edge))) {
            if (isInside(pos, centre, edge, top)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            blocks++;
            Ratings held = BlockRatings.of(state);
            if (required.isEmpty()) {
                unrated++;
            } else if (!held.meets(required.get())) {
                unrated++;
                shortOn.addAll(held.shortfalls(required.get()));
            }
        }
        return new Status(required.isPresent() && unrated == 0, blocks, unrated, Set.copyOf(shortOn));
    }

    private static boolean isInside(BlockPos pos, BlockPos centre, int edge, int top) {
        int dy = pos.getY() - centre.getY();
        return Math.abs(pos.getX() - centre.getX()) < edge
                && Math.abs(pos.getZ() - centre.getZ()) < edge
                && dy > 0
                && dy < top;
    }
}
