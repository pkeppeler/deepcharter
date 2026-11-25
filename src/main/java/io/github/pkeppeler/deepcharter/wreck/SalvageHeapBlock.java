package io.github.pkeppeler.deepcharter.wreck;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Where a broken storage block's contents are kept (ADR 0072, 0079): ruined, not erased, as a wreck keeps its cargo. Anyone may open it
 * and take what is in it; breaking it drops what is left. A visit never touches it ({@code deepcharter:never_settled}).
 */
public final class SalvageHeapBlock extends BaseEntityBlock {
    SalvageHeapBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SalvageHeapBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof MenuProvider heap) {
            player.openMenu(heap);
        }
        return InteractionResult.SUCCESS;
    }
}
