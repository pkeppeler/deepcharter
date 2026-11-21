package io.github.pkeppeler.deepcharter.layer;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The server's side of brute-forcing a pod-only block ({@link HandBreak}, ADR 0069): the durability a block costs, the
 * sparks and grinding while it is mined, and the break itself, which drops nothing. Called by
 * {@code ServerPlayerGameModeMixin}, once a tick while the player is mining and once when the break is asked for.
 *
 * <p>A block costs {@code share(layer) * maxDamage} points. The tool is damaged directly, by
 * {@link ItemStack#setDamageValue}, and never by {@code hurtAndBreak}: that is the path Unbreaking reduces. Mending
 * repairs from experience and never touches the cost. The cost is spread evenly over the first {@code breakTicks - 1}
 * ticks of mining, all but its last point; the break pays what is left, so a pick that would have to give more than it
 * has shatters on the way, before the client predicts a break, and the block stays. A break that comes early (a short
 * stop) pays the rest in one go.
 */
public final class HandBreakCost {
    private HandBreakCost() {}

    /**
     * One tick of mining: spends this tick's share of the cost, then the sparks, the grinding and the line that says the
     * pick is burning out, on their cadences. Returns the ticks mined so far, which the caller keeps for the next tick
     * and for {@link #breakByHand}. A block that is not pod-only, or a tool that does not bite, returns {@code mined}
     * unchanged, except that a block that is not pod-only starts the count again.
     */
    public static int grind(ServerPlayer player, ServerLevel level, BlockPos pos, Direction face, int mined) {
        BlockState state = level.getBlockState(pos);
        if (!HandBreak.governs(player, level, pos, state)) {
            return 0;
        }
        int layer = HandBreak.layerOf(level, pos);
        ItemStack tool = player.getMainHandItem();
        if (!HandBreak.bites(tool, layer)) {
            return mined;
        }
        HandBreakTuning tuning = LayerTuning.DEFAULT.handBreak();
        int ticks = mined + 1;
        if (ticks < tuning.breakTicks()) {
            int total = totalCost(tool, layer);
            spend(player, tool, spentBy(total, ticks) - spentBy(total, mined));
        }
        // The pick may have shattered in that spend: the effects are for a pick that is still burning.
        if (!tool.isEmpty()) {
            effects(player, level, pos, face, ticks, tuning);
        }
        return ticks;
    }

    /**
     * Breaks a pod-only block for a player who has been mining it for {@code mined} ticks: refused for a tool that does
     * not bite, one whose block a {@code PlayerBlockBreakEvents.BEFORE} listener vetoes (nothing is charged), or one
     * without the durability left to pay, which shatters. The block goes without a drop, a pulverised block, so brute
     * force is never an ore exploit, but with the events a hand break fires, so a gas pocket still vents and protection
     * mods still veto. Creative players never come here. A refusal sends the client the block back, so it shows no ghost.
     */
    public static boolean breakByHand(ServerPlayer player, ServerLevel level, BlockPos pos, int mined) {
        boolean broken = tryBreak(player, level, pos, mined);
        if (!broken) {
            player.connection.send(new ClientboundBlockUpdatePacket(pos, level.getBlockState(pos)));
        }
        return broken;
    }

    private static boolean tryBreak(ServerPlayer player, ServerLevel level, BlockPos pos, int mined) {
        // Vanilla's own refusals in destroyBlock, which this replaces for a pod-only block: adventure mode and the
        // like.
        if (player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer())) {
            return false;
        }
        int layer = HandBreak.layerOf(level, pos);
        ItemStack tool = player.getMainHandItem();
        if (!HandBreak.bites(tool, layer)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, state, blockEntity)) {
            PlayerBlockBreakEvents.CANCELED.invoker().onBlockBreakCanceled(level, player, pos, state, blockEntity);
            return false;
        }
        int total = totalCost(tool, layer);
        int due = total - spentBy(total, mined);
        // A pick that has just enough is used up by the block and the block still goes; one with less shatters first.
        boolean enough = tool.getDamageValue() + due <= tool.getMaxDamage();
        spend(player, tool, due);
        if (!enough) {
            return false;
        }
        BlockState broken = state.getBlock().playerWillDestroy(level, pos, state, player);
        if (!level.removeBlock(pos, false)) {
            return false;
        }
        state.getBlock().destroy(level, pos, broken);
        PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, player, pos, state, blockEntity);
        return true;
    }

    /** The durability one block of {@code layer} costs this tool. */
    static int totalCost(ItemStack tool, int layer) {
        return (int) Math.round(LayerTuning.DEFAULT.handBreak().shareFor(layer) * tool.getMaxDamage());
    }

    /**
     * The points of {@code total} spent after {@code ticks} ticks of mining, evenly over the first {@code breakTicks - 1}
     * and all but the last point, which the break takes: a pick that can pay the whole cost survives the mining with one
     * point to spare, and one that cannot shatters while the client is still mining, before it predicts a break.
     */
    private static int spentBy(int total, int ticks) {
        int span = LayerTuning.DEFAULT.handBreak().breakTicks() - 1;
        return (int) ((long) Math.max(total - 1, 0) * Math.min(ticks, span) / span);
    }

    /** Damages {@code tool} by {@code amount} directly, and shatters it, as vanilla does, when the damage reaches its maximum. */
    private static void spend(ServerPlayer player, ItemStack tool, int amount) {
        if (amount <= 0) {
            return;
        }
        int damage = tool.getDamageValue() + amount;
        if (damage < tool.getMaxDamage()) {
            tool.setDamageValue(damage);
            return;
        }
        ItemStack broken = tool.copy();
        tool.shrink(1);
        player.onEquippedItemBroken(broken, EquipmentSlot.MAINHAND);
        player.awardStat(Stats.ITEM_BROKEN.get(broken.getItem()));
    }

    private static void effects(
            ServerPlayer player, ServerLevel level, BlockPos pos, Direction face, int ticks, HandBreakTuning tuning) {
        if (ticks % tuning.sparkTicks() == 0) {
            Vec3 at = Vec3.atCenterOf(pos).relative(face, 0.5);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 6, 0.2, 0.2, 0.2, 0.3);
        }
        if (ticks % tuning.soundTicks() == 0) {
            level.playSound(null, pos, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.7F, 0.9F);
        }
        if (ticks % tuning.messageTicks() == 1) {
            player.sendOverlayMessage(Component.translatable("deepcharter.layer.pod_only.burning"));
        }
    }
}
