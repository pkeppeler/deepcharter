package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.Arrays;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a block holds, read from block tags (#989, ADR 0076), so a data pack changes a rating and a new block is rated by a JSON line:
 * a block is in {@code deepcharter:rating/<axis>/<level>} for each axis it holds at that level, and its rating on the axis is the
 * highest level it is listed at. A block in no tag of an axis has level 0 there. {@link #UNRATED} lists the blocks that are deliberately
 * rated nothing (natural blocks, plants, ore), so that a test can tell a block nobody decided on from one that was decided.
 *
 * <p>Tags are synced to the client, so the client reads the same ratings as the server.
 */
public final class BlockRatings {
    /** Blocks that were looked at and given no rating: the registry test fails on a mod block in this tag and in a rating tag. */
    public static final TagKey<Block> UNRATED = tag("rating/none");

    private BlockRatings() {}

    /** The block tag of blocks that hold {@code axis} at {@code level}. */
    public static TagKey<Block> tag(RatingAxis axis, int level) {
        return tag("rating/" + axis.id() + "/" + level);
    }

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }

    /** What {@code state}'s block holds. A block no tag lists holds nothing ({@link Ratings#NONE}). */
    public static Ratings of(BlockState state) {
        int max = RatingTuning.DEFAULT.maxLevel();
        int[] levels = new int[RatingAxis.values().length];
        for (RatingAxis axis : RatingAxis.values()) {
            for (int level = max; level >= 1; level--) {
                if (state.is(tag(axis, level))) {
                    levels[axis.ordinal()] = level;
                    break;
                }
            }
        }
        return new Ratings(
                levels[RatingAxis.HEAT.ordinal()],
                levels[RatingAxis.PRESSURE.ordinal()],
                levels[RatingAxis.FLOODING.ordinal()],
                levels[RatingAxis.QUAKE.ordinal()]);
    }

    /** True when a tag says something about the block: it holds something, or it is in {@link #UNRATED}. */
    public static boolean isListed(BlockState state) {
        return state.is(UNRATED) || Arrays.stream(RatingAxis.values()).anyMatch(axis -> isRatedOn(state, axis));
    }

    private static boolean isRatedOn(BlockState state, RatingAxis axis) {
        for (int level = 1; level <= RatingTuning.DEFAULT.maxLevel(); level++) {
            if (state.is(tag(axis, level))) {
                return true;
            }
        }
        return false;
    }
}
