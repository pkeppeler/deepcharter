package io.github.pkeppeler.deepcharter.repair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A burning flare (#985): a flare stood in the floor, giving real block light, so the light engine, the natural spawn rule and anything that reads
 * block light see it with no special case. It puts itself out at the tick that {@link ThrownKitEntity} scheduled, which a chunk keeps through an
 * unload, so a flare never outlives its burn. Nothing places it by hand (it has no item) and it drops nothing.
 */
public final class FlareLightBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(5, 0, 5, 11, 12, 11);
    private static final int SPARK_ONE_IN = 3;

    FlareLightBlock(Properties properties) {
        super(properties
                .noCollision()
                .noOcclusion()
                .instabreak()
                .pushReaction(PushReaction.POPPED)
                .noLootTable()
                .lightLevel(state -> ThrownKitTuning.DEFAULT.flare().lightLevel()));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** The flare burns out. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        level.removeBlock(pos, false);
        level.sendParticles(
                ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5, 6, 0.1, 0.2, 0.1, 0.01);
        level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.4f, 1.4f);
    }

    /** Sparks and smoke while it burns. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.7;
        double z = pos.getZ() + 0.5;
        level.addParticle(
                ParticleTypes.FLAME,
                x + (random.nextDouble() - 0.5) * 0.15,
                y,
                z + (random.nextDouble() - 0.5) * 0.15,
                0,
                0.02,
                0);
        if (random.nextInt(SPARK_ONE_IN) == 0) {
            level.addParticle(ParticleTypes.SMOKE, x, y + 0.2, z, 0, 0.03, 0);
        }
    }
}
