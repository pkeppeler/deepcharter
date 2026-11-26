package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
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

/** What the charge tests share: using a charge in the main hand, and checking a block after the blast. */
public final class ChargeUse {
    private ChargeUse() {}

    /** Puts one {@code charge} in the player's hand, clears its cooldown and uses it. */
    public static InteractionResult use(ServerPlayer player, Consumable charge) {
        player.getInventory()
                .setItem(player.getInventory().getSelectedSlot(), new ItemStack(RepairRegistry.item(charge)));
        player.getCooldowns()
                .removeCooldown(
                        player.getCooldowns().getCooldownGroup(player.getItemInHand(InteractionHand.MAIN_HAND)));
        return player.gameMode.useItem(
                player, player.level(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
    }

    /**
     * The hull guard (#1458): a pod with exactly {@code wreckingHull} is refused {@code charge} and keeps it, then gets {@code margin} more, so
     * the charge the test sets next is set with just over the guard.
     */
    public static void refuseAtGuardThenClearIt(
            GameTestHelper helper, PodEntity pod, Consumable charge, float wreckingHull, float margin) {
        if (!(pod.getControllingPassenger() instanceof ServerPlayer pilot)) {
            throw failure(helper, "the pod has no pilot to set the charge");
        }
        pod.setHull(wreckingHull);
        if (use(pilot, charge).consumesAction()) {
            throw failure(
                    helper, "a pod with %.2f hull, what the blast and the drop cost, should be refused", wreckingHull);
        }
        if (pilot.getInventory().countItem(RepairRegistry.item(charge)) != 1) {
            throw failure(helper, "a charge refused for a low hull must stay in the pack");
        }
        pod.setHull(wreckingHull + margin);
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
