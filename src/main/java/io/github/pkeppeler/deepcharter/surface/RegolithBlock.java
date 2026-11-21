package io.github.pkeppeler.deepcharter.surface;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ColorRGBA;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.ColoredFallingBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Loose regolith (#836): falls like sand, but a pod in the landing cell refuses the block, which spills as an item. */
final class RegolithBlock extends ColoredFallingBlock {
    RegolithBlock(ColorRGBA dustColor, BlockBehaviour.Properties properties) {
        super(dustColor, properties);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        // entity-query: runs once per landing, in a loaded cell
        return !(level instanceof Level world)
                || world.getEntitiesOfClass(PodEntity.class, new AABB(pos)).isEmpty();
    }
}
