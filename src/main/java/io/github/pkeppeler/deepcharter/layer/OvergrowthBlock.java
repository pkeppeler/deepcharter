package io.github.pkeppeler.deepcharter.layer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The moss growth of a bore left unvisited (#974): a soft full block. The only thing it does by itself is shed the odd
 * spore into the bore, so a pilot sees the film before the bore is closed. What grows it, and where, is
 * {@link Overgrowth}.
 */
public final class OvergrowthBlock extends Block {
    /** One display tick in this many sheds a spore into an open side. */
    private static final int SPORE_ODDS = 30;

    public OvergrowthBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(SPORE_ODDS) != 0) {
            return;
        }
        Direction side = Direction.getRandom(random);
        BlockPos open = pos.relative(side);
        if (level.getBlockState(open).isAir()) {
            level.addParticle(
                    ParticleTypes.SPORE_BLOSSOM_AIR,
                    open.getX() + random.nextDouble(),
                    open.getY() + random.nextDouble(),
                    open.getZ() + random.nextDouble(),
                    0,
                    0,
                    0);
        }
    }
}
