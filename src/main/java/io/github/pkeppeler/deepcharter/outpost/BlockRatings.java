package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a block holds, read from block tags (#989, ADR 0076), so a data pack changes a rating and a new block is rated by a JSON line:
 * a block is in {@code deepcharter:rating/<axis>/<level>} for each axis it holds at that level, and its rating on the axis is the
 * highest level it is listed at. A block in no tag of an axis has level 0 there. {@code deepcharter:rating/none} lists the blocks that are deliberately
 * rated nothing (natural blocks, plants, ore), so that a test can tell a block nobody decided on from one that was decided.
 *
 * <p>Tags are synced to the client, so the client reads the same ratings as the server.
 */
public final class BlockRatings {
    /** {@code TAGS[axis][level - 1]}: the tag of blocks that hold that axis at that level, built once. */
    private static final List<List<TagKey<Block>>> TAGS = Arrays.stream(RatingAxis.values())
            .map(axis -> IntStream.rangeClosed(1, RatingTuning.DEFAULT.maxLevel())
                    .mapToObj(level -> tag("rating/" + axis.id() + "/" + level))
                    .toList())
            .toList();

    private BlockRatings() {}

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }

    /** What {@code state}'s block holds. A block no tag lists holds nothing ({@link Ratings#NONE}). */
    public static Ratings of(BlockState state) {
        int[] levels = new int[RatingAxis.values().length];
        for (RatingAxis axis : RatingAxis.values()) {
            List<TagKey<Block>> tags = TAGS.get(axis.ordinal());
            for (int level = tags.size(); level >= 1; level--) {
                if (state.is(tags.get(level - 1))) {
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
}
