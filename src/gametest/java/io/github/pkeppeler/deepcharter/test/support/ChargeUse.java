package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** What the charge tests share: using a charge in the main hand, packing rock round a seated pilot's pod, and checking a block after the blast. */
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

    /**
     * Fills every air cell within {@code radius} of the middle of {@code pilot}'s {@code pod} with stone, as a shaft caved in on it, but the one the pilot's eyes are in,
     * which would choke it. Returns the cells filled.
     */
    public static List<BlockPos> packRound(ServerPlayer pilot, PodEntity pod, int radius) {
        ServerLevel level = pilot.level();
        BlockPos middle = BlockPos.containing(pod.getBoundingBox().getCenter());
        Vec3 eye = pilot.getEyePosition();
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos cell : BlockPos.betweenClosed(
                middle.offset(-radius, -radius, -radius), middle.offset(radius, radius, radius))) {
            if (level.getBlockState(cell).isAir() && !new AABB(cell).contains(eye)) {
                level.setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
                cells.add(cell.immutable());
            }
        }
        return cells;
    }
}
