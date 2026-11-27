package io.github.pkeppeler.deepcharter.surface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The collapse of a fungal stalk: breaking any block of one brings everything above it down at once, as breaking sugar
 * cane does, in a spore puff. Atmosphere only: it gives a player nothing and takes nothing from one.
 *
 * <p>What falls is what hangs from the break. An ordinary stalk is a column of upright stalk blocks: everything above the break. A great
 * stalk ({@link GreatStalkBlock}, a braid of three thin trunks with shelves, {@link GreatStalkBraid}) is every great block above the break
 * that touches it face to face through great blocks at or above the break's level: the trunk that broke, above the break, with the
 * shelves on it. A cap block hangs from what it joins, so a lip hanging under a shelf falls with the shelf, however low it hangs. The
 * trunks never touch each other, so the rule reaches no further than the trunk that broke and what is attached to it: the other trunks
 * stand, and so does a shelf held by one. A block that was only joined through the broken cell, sideways (the dog-leg where a trunk
 * steps round the ring, the outer half of a shelf), is no longer joined to the ground and falls too.
 *
 * <p>Every block of it bursts in the tick of the break. No drop is made here but the burst's own: a burst block drops
 * what {@link Block#getDrops} gives for it, the loot table that breaking it by hand rolls, and every block is removed
 * exactly once, so what a collapse drops is what breaking each of its blocks by hand drops, none lost and none twice.
 * The drops fly out from the block instead of popping up from it.
 */
final class StalkCollapse {
    /**
     * The most blocks one collapse takes, so that no chain of touching great stalks makes a collapse cost a lot: the most
     * that can hang together, two giants whose caps touch across a chunk edge and a great stalk on them (at most 90
     * blocks, {@code StalkCollapseTest.YIELD_MOST}).
     */
    private static final int MOST_BLOCKS = 2 * GiantLayout.MOST_CELLS + 128;

    private static final Direction[] JOINS = {
        Direction.UP, Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    /** The faces of a removed great block, other than the one above, that something can hang from it by. */
    private static final Direction[] HELD_BY = {
        Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private StalkCollapse() {}

    /** An upright stalk block, a great stalk block or a cap block: the blocks that fall. */
    static boolean collapses(BlockState state) {
        return great(state) || ordinary(state);
    }

    private static boolean great(BlockState state) {
        return state.getBlock() instanceof GreatStalkBlock;
    }

    private static boolean ordinary(BlockState state) {
        return state.getBlock() instanceof FungalStalkBlock
                && state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y;
    }

    /** A block that hangs from what it joins instead of standing: a cap block. */
    private static boolean hangs(BlockState state) {
        return state.getBlock() instanceof GreatStalkBlock block && block.isCap();
    }

    /** Whether the chunk of {@code pos} is loaded: a collapse never loads one, so what is in an unloaded chunk is not joined to anything. */
    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** Whether the block below is a solid floor for {@code pos}. */
    private static boolean standsOnSomething(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /** Whether the hanging blocks under {@code start}, itself included, end in the air and not on anything. */
    private static boolean hangsFree(ServerLevel level, BlockPos start) {
        BlockPos pos = start;
        while (hangs(level.getBlockState(pos))) {
            pos = pos.below();
        }
        return level.getBlockState(pos).isAir();
    }

    /**
     * A block of a stalk, {@code removed}, was removed at {@code pos}: what was above it, and what hung from it and has
     * nothing else to hang from, bursts in the same tick. A great block's removal takes the great block above it (and
     * all that touches that) whatever else holds it; a block beside or below it falls only if nothing joins it to the
     * ground any more. An upright stalk never holds a great block up, and neither does a giant's root flare (#1218): a parasol's arms
     * stand on its flares, and a player scraping its foot must not fell it.
     */
    static void removed(ServerLevel level, BlockPos pos, BlockState removed) {
        if (collapses(level.getBlockState(pos))) {
            return;
        }
        List<BlockPos> anchors = new ArrayList<>();
        Set<BlockPos> claimed = new LinkedHashSet<>();
        BlockPos above = pos.above();
        BlockState up = level.getBlockState(above);
        boolean flare = removed.getBlock() instanceof GiantFungusBlocks.FlareBlock;
        if (collapses(up) && (great(removed) || !great(up)) && !(flare && great(up))) {
            anchors.add(above);
            claimed.addAll(connected(level, above));
        }
        if (great(removed)) {
            for (Direction direction : HELD_BY) {
                BlockPos next = pos.relative(direction);
                if (loaded(level, next)
                        && !claimed.contains(next)
                        && collapses(level.getBlockState(next))
                        && !standsOnSomething(level, next)
                        && !grounded(level, next)) {
                    anchors.add(next);
                    claimed.addAll(connected(level, next));
                }
            }
        }
        if (claimed.isEmpty()) {
            return;
        }
        boolean great = anchors.stream().anyMatch(anchor -> great(level.getBlockState(anchor)));
        for (BlockPos fall : claimed) {
            BlockState state = level.getBlockState(fall);
            if (collapses(state)) {
                burst(level, fall, state, great);
            }
        }
        cough(level, pos, great);
    }

    /**
     * Whether the stalk blocks joined to {@code start}, face to face, stand on the ground: one of them has something
     * solid under it that is not a stalk block. What it cannot tell (more blocks than a collapse takes, or a join into
     * a chunk that is not loaded) it counts as standing.
     */
    private static boolean grounded(ServerLevel level, BlockPos start) {
        Set<BlockPos> found = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        found.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (found.size() >= MOST_BLOCKS || standsOnGround(level, pos)) {
                return true;
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (loaded(level, next) && collapses(level.getBlockState(next)) && found.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    private static boolean standsOnGround(ServerLevel level, BlockPos pos) {
        return standsOnSomething(level, pos) && !collapses(level.getBlockState(pos.below()));
    }

    /** Whether the block {@code next}, one {@code direction} from the falling {@code state}, is joined to it, as the world stands. */
    private static boolean staticJoin(
            ServerLevel level,
            Set<BlockPos> falling,
            Direction direction,
            BlockState state,
            BlockPos next,
            BlockState nextState) {
        return switch (direction) {
            case UP -> collapses(nextState);
            case DOWN -> hangs(nextState) && hangsFree(level, next) && pendantOf(level, falling, next);
            default -> great(state) && great(nextState);
        };
    }

    /**
     * Whether the cap blocks joined to {@code start} hang only from what is falling: none of them touches a trunk block that
     * stands, or a cap block of another trunk's, outside {@code falling}. A cap tucked under the trunk that falls but joined to a trunk
     * that stands does not fall with it.
     */
    private static boolean pendantOf(ServerLevel level, Set<BlockPos> falling, BlockPos start) {
        Set<BlockPos> pendant = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        pendant.add(start);
        queue.add(start);
        while (!queue.isEmpty() && pendant.size() < MOST_BLOCKS) {
            BlockPos pos = queue.poll();
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (!loaded(level, next)) {
                    // What is not loaded may be holding it up.
                    return false;
                }
                BlockState nextState = level.getBlockState(next);
                if (falling.contains(next) || !collapses(nextState)) {
                    continue;
                }
                if (!hangs(nextState)) {
                    return false;
                }
                if (pendant.add(next)) {
                    queue.add(next);
                }
            }
        }
        return pendant.size() < MOST_BLOCKS;
    }

    /**
     * The blocks that fall with {@code start}: up the columns, across between great stalk blocks that touch, and down
     * the cap blocks that hang free under them.
     */
    private static Set<BlockPos> connected(ServerLevel level, BlockPos start) {
        Set<BlockPos> found = new LinkedHashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        found.add(start);
        queue.add(start);
        while (!queue.isEmpty() && found.size() < MOST_BLOCKS) {
            BlockPos pos = queue.poll();
            BlockState state = level.getBlockState(pos);
            for (Direction direction : JOINS) {
                BlockPos next = pos.relative(direction);
                if (!loaded(level, next)) {
                    // A collapse at a chunk's edge never loads the chunk beside it: what is not loaded is not joined.
                    continue;
                }
                BlockState nextState = level.getBlockState(next);
                if (staticJoin(level, found, direction, state, next, nextState) && found.add(next)) {
                    queue.add(next);
                }
            }
        }
        return found;
    }

    /** A block bursts: it turns to a puff and its drops fly out. */
    private static void burst(ServerLevel level, BlockPos pos, BlockState state, boolean great) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, null);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        RandomSource random = level.getRandom();
        double speed = SurfaceTuning.DEFAULT.scatterSpeed();
        for (ItemStack drop : drops) {
            double angle = random.nextDouble() * Math.PI * 2;
            double out = speed * (0.4 + 0.6 * random.nextDouble());
            level.addFreshEntity(new ItemEntity(
                    level,
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5,
                    drop,
                    Math.cos(angle) * out,
                    0.15 + 0.15 * random.nextDouble(),
                    Math.sin(angle) * out));
        }
        level.levelEvent(2001, pos, Block.getId(state));
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        level.sendParticles(ParticleTypes.POOF, x, y, z, great ? 5 : 3, 0.4, 0.4, 0.4, 0.02);
        level.sendParticles(ParticleTypes.WHITE_ASH, x, y, z, great ? 14 : 8, 0.6, 0.5, 0.6, 0.02);
    }

    /** The wet cough of a collapse: a huff of breath and a slap of something wet, lower for a great stalk. */
    private static void cough(ServerLevel level, BlockPos pos, boolean great) {
        RandomSource random = level.getRandom();
        float pitch = great ? 0.5F : 0.8F;
        float volume = great ? 1.2F : 0.8F;
        float jitter = random.nextFloat() * 0.2F;
        level.playSound(null, pos, SoundEvents.PUFFER_FISH_BLOW_OUT, SoundSource.BLOCKS, volume, pitch + jitter);
        level.playSound(null, pos, SoundEvents.SLIME_BLOCK_BREAK, SoundSource.BLOCKS, volume, pitch * 0.7F + jitter);
    }
}
