package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** What the charge tests share: using a charge in the main hand, the hull guard, and checking a block after the blast. */
public final class ChargeUse {
    /** Hull over the guard that {@link #refuseAtGuardThenClearIt} leaves a pod with, so it should just survive its charge. */
    private static final float GUARD_MARGIN = 0.05f;

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
     * The hull guard (#1458): a pod with exactly {@code wreckingHull} is refused {@code charge} and keeps it, then gets {@link #GUARD_MARGIN}
     * more, so the charge the test sets next is set with just over the guard.
     */
    public static void refuseAtGuardThenClearIt(
            GameTestHelper helper, PodEntity pod, Consumable charge, float wreckingHull) {
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
        pod.setHull(wreckingHull + GUARD_MARGIN);
    }

    /**
     * After {@link #refuseAtGuardThenClearIt}: the pod lived through its charge's blast and the drop into the hole, rests at {@code bottomY}, and
     * lost exactly {@code wreckingHull} of the {@code hullAtSet} it set the charge with.
     */
    public static void expectSurvivedBlastAndDrop(
            GameTestHelper helper, PodEntity pod, float hullAtSet, float wreckingHull, int bottomY) {
        if (Wrecks.isWreck(pod) || pod.hull() <= 0) {
            throw failure(helper, "a pod %.2f over the guard should live, its hull is %.2f", GUARD_MARGIN, pod.hull());
        }
        if (Math.abs(pod.getY() - bottomY) > 0.01) {
            throw failure(
                    helper, "the pod should rest at the bottom of the hole, %d, it is at %.2f", bottomY, pod.getY());
        }
        double lost = hullAtSet - pod.hull();
        if (Math.abs(lost - wreckingHull) > 0.01) {
            throw failure(
                    helper, "the blast and the drop should cost %.2f hull, the pod lost %.2f", wreckingHull, lost);
        }
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
