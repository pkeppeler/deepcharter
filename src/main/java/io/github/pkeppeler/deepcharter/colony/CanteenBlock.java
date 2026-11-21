package io.github.pkeppeler.deepcharter.colony;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The canteen dispenser of the Lamp and Pick. It gives each player one ration per in-game day ({@link ColonyRations#today}) and
 * refuses a second. The day of a player's last ration is in their {@link RationLedger}, so it holds across restarts and is
 * the player's own: a crewmate's ration does not use yours. A player whose ledger cannot be read (another version's data, logged
 * once) gets the refusal and nothing is written.
 */
final class CanteenBlock extends KitFacingBlock {
    CanteenBlock(Properties properties, VoxelShape north) {
        super(properties, north);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
            long today = ColonyRations.today(serverLevel);
            Optional<RationLedger> ledger = Versioned.readable(serverPlayer, ColonyRations.LEDGER);
            if (ledger.isEmpty() || ledger.get().canteenDay() == today) {
                serverPlayer.sendOverlayMessage(Component.translatable("colony.deepcharter.canteen.refused"));
            } else {
                ColonyRations.change(serverPlayer, held -> new RationLedger(held.starterGiven(), today));
                ColonyRations.give(serverPlayer, 1);
                serverPlayer.sendOverlayMessage(Component.translatable("colony.deepcharter.canteen.dispensed"));
            }
        }
        return InteractionResult.SUCCESS;
    }
}
