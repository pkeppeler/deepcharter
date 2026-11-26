package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** What the consumable tests share: using a consumable in the main hand, and checking a block after a charge's blast. */
public final class ChargeUse {
    private ChargeUse() {}

    /** Puts one {@code charge} (any consumable) in the player's hand, clears its cooldown and uses it. */
    public static InteractionResult use(ServerPlayer player, Consumable charge) {
        player.getInventory()
                .setItem(player.getInventory().getSelectedSlot(), new ItemStack(RepairRegistry.item(charge)));
        player.getCooldowns()
                .removeCooldown(
                        player.getCooldowns().getCooldownGroup(player.getItemInHand(InteractionHand.MAIN_HAND)));
        return player.gameMode.useItem(
                player, player.level(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
    }

    public static void expectBlock(
            GameTestHelper helper, ServerLevel level, BlockPos pos, Block expected, String what) {
        if (!level.getBlockState(pos).is(expected)) {
            throw failure(
                    helper,
                    "%s: %s should be %s, was %s",
                    what,
                    pos,
                    expected,
                    level.getBlockState(pos).getBlock());
        }
    }
}
