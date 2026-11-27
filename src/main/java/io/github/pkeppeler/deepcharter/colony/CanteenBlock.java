package io.github.pkeppeler.deepcharter.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The canteen dispenser of the Lamp and Pick. It gives a ration on every use, with no limit (#1439), and keeps no record, so it
 * reads nothing a save could break.
 */
final class CanteenBlock extends KitFacingBlock {
    CanteenBlock(Properties properties, VoxelShape north) {
        super(properties, north);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            ColonyRations.give(serverPlayer, 1);
            serverPlayer.sendOverlayMessage(Component.translatable("colony.deepcharter.canteen.dispensed"));
        }
        return InteractionResult.SUCCESS;
    }
}
