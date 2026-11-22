package io.github.pkeppeler.deepcharter.surface;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The collapse of a fungal stalk: breaking any block of one brings everything above it down, in a spore puff. Atmosphere only: it
 * gives a player nothing and takes nothing from one.
 *
 * <p>What falls is the blocks above the break that are joined to it: an ordinary stalk is a column of upright stalk blocks, and a great
 * stalk (its trunk and cap, {@link GreatStalkBlock}) is every great block above the break, in all four columns of its trunk and its
 * cap, since they touch. The collapse is a sag: after {@link SurfaceTuning#collapseDelayTicks()} each step lowers all of it by one
 * block, and the layer that reaches the break bursts instead of moving. The next step is a scheduled tick on the block just above the
 * break, so a collapse survives a save and a chunk unload, and a second break above the first starts a second collapse that the first
 * cannot confuse, since every step reads the world.
 *
 * <p>No drop is made here: a burst block drops what {@link Block#getDrops} gives for it, the loot table that breaking it by hand rolls,
 * and every block is removed exactly once (it bursts, or it moves down), so what a collapse drops is what breaking each of its blocks by
 * hand drops, none lost and none twice. The drops fly out from the block instead of popping up from it.
 */
final class StalkCollapse {
    /** The most blocks one collapse lowers, so that no chain of touching great stalks makes a step cost a lot. */
    private static final int MOST_BLOCKS = 1024;

    private static final Direction[] JOINS = {
        Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    /**
     * True while a step changes blocks: the blocks it sets to air are removals that must not start a collapse of their own. The server
     * thread is the only one that changes blocks, so no lock.
     */
    private static boolean stepping;

    private StalkCollapse() {}

    /** An upright stalk block, a great stalk block or a cap block: the blocks that fall. */
    static boolean collapses(BlockState state) {
        return great(state)
                || (state.getBlock() instanceof FungalStalkBlock
                        && state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y);
    }

    private static boolean great(BlockState state) {
        return state.getBlock() instanceof GreatStalkBlock;
    }

    /** A block of a stalk was removed at {@code pos}: if a stalk stands above, it sags and falls. */
    static void removed(ServerLevel level, BlockPos pos) {
        if (stepping) {
            return;
        }
        BlockPos above = pos.above();
        BlockState up = level.getBlockState(above);
        if (!collapses(up) || collapses(level.getBlockState(pos))) {
            return;
        }
        level.playSound(null, pos, SoundEvents.HONEY_BLOCK_SLIDE, SoundSource.BLOCKS, 0.8F, great(up) ? 0.4F : 0.6F);
        level.scheduleTick(above, up.getBlock(), SurfaceTuning.DEFAULT.collapseDelayTicks());
    }

    /** One step of the collapse above {@code above}, the block that was just over the break, which is the one the tick is on. */
    static void step(ServerLevel level, BlockPos above) {
        BlockPos broken = above.below();
        if (!collapses(level.getBlockState(above)) || collapses(level.getBlockState(broken))) {
            return;
        }
        List<BlockPos> falling = connected(level, above);
        boolean great = falling.stream().anyMatch(pos -> great(level.getBlockState(pos)));
        stepping = true;
        try {
            int burst = 0;
            // Lowest first, so that the block a cell moves into has been emptied: by the layer below it moving, or by
            // bursting.
            for (BlockPos pos : falling) {
                BlockState state = level.getBlockState(pos);
                BlockPos target = pos.below();
                if (pos.getY() == above.getY() || !level.getBlockState(target).isAir() || occupied(level, target)) {
                    burst(level, pos, state, great);
                    burst++;
                } else {
                    level.setBlock(target, state, Block.UPDATE_CLIENTS);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            if (burst > 0) {
                cough(level, broken, great);
            }
        } finally {
            stepping = false;
        }
        BlockState next = level.getBlockState(above);
        if (collapses(next)) {
            level.scheduleTick(above, next.getBlock(), SurfaceTuning.DEFAULT.collapseStepTicks());
        }
    }

    /**
     * Whether a creature stands in the cell. A block never slides into one: it bursts instead, so the collapse can never smother or crush
     * anyone (the rim of a great stalk's cap overhangs its trunk and slides down beside it). Items do not count: they are what it throws.
     */
    private static boolean occupied(ServerLevel level, BlockPos cell) {
        // entity-query: the cell is one the collapse is about to fill, in a loaded chunk, so a miss cannot happen
        return !level.getEntities(
                        (Entity) null,
                        new AABB(cell),
                        entity -> !(entity instanceof ItemEntity) && !entity.isSpectator())
                .isEmpty();
    }

    /** The blocks that fall with {@code start}, lowest first: up the columns, and across between great stalk blocks. */
    private static List<BlockPos> connected(ServerLevel level, BlockPos start) {
        Set<BlockPos> found = new LinkedHashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        found.add(start);
        queue.add(start);
        while (!queue.isEmpty() && found.size() < MOST_BLOCKS) {
            BlockPos pos = queue.poll();
            BlockState state = level.getBlockState(pos);
            for (Direction direction : JOINS) {
                BlockPos next = pos.relative(direction);
                if (!level.getChunkSource().hasChunk(next.getX() >> 4, next.getZ() >> 4)) {
                    // A collapse at a chunk's edge never loads the chunk beside it: what is not loaded is not joined.
                    continue;
                }
                BlockState nextState = level.getBlockState(next);
                boolean joined = direction == Direction.UP ? collapses(nextState) : great(state) && great(nextState);
                if (joined && found.add(next)) {
                    queue.add(next);
                }
            }
        }
        return found.stream().sorted(Comparator.comparingInt(BlockPos::getY)).toList();
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

    /** The wet cough of a step: a huff of breath and a slap of something wet, lower for a great stalk. */
    private static void cough(ServerLevel level, BlockPos pos, boolean great) {
        RandomSource random = level.getRandom();
        float pitch = great ? 0.5F : 0.8F;
        float volume = great ? 1.2F : 0.8F;
        float jitter = random.nextFloat() * 0.2F;
        level.playSound(null, pos, SoundEvents.PUFFER_FISH_BLOW_OUT, SoundSource.BLOCKS, volume, pitch + jitter);
        level.playSound(null, pos, SoundEvents.SLIME_BLOCK_BREAK, SoundSource.BLOCKS, volume, pitch * 0.7F + jitter);
    }
}
