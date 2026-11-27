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
 * <p>Any tool does it, in any layer ({@link HandBreak#wearOf}). A durable tool (diamond and better) pays
 * {@code durableShare * maxDamage} points, spread evenly over the first {@code breakTicks - 1} ticks of mining, all
 * but its last point; the break pays the rest. Any other tool pays all it has left: each tick takes its share of the
 * durability down to a last point, which the break takes, so it is destroyed as the block goes and never before. Either
 * way a tool never wears out on the way, whatever it has left (a tick spends at most all but its last point), so the
 * block always goes. The tool is damaged directly, by {@link ItemStack#setDamageValue}, and never by
 * {@code hurtAndBreak}: that is the path Unbreaking reduces. Mending repairs from experience and never touches the
 * cost. A break that comes early (a short stop) pays the rest in one go.
 */
public final class HandBreakCost {
    private HandBreakCost() {}

    /**
     * One tick of mining: spends this tick's share of the cost, then the sparks, the grinding and the line that says
     * the pick is burning out, on their cadences. Returns the ticks mined so far, which the caller keeps for the next
     * tick and for {@link #breakByHand}. A block that is not pod-only, or an item that is no tool, returns {@code
     * mined} unchanged, except that a block that is not pod-only starts the count again.
     */
    public static int grind(ServerPlayer player, ServerLevel level, BlockPos pos, Direction face, int mined) {
        BlockState state = level.getBlockState(pos);
        if (!HandBreak.governs(player, level, pos, state)) {
            return 0;
        }
        ItemStack tool = player.getMainHandItem();
        HandBreak.Wear wear = HandBreak.wearOf(tool);
        if (wear == HandBreak.Wear.NONE) {
            return mined;
        }
        HandBreakTuning tuning = LayerTuning.DEFAULT.handBreak();
        int ticks = mined + 1;
        if (ticks < tuning.breakTicks() && wear != HandBreak.Wear.FREE) {
            // Never the last point: that one is the break's, so the tool cannot give out with the block standing.
            int spend =
                    Math.min(spentThisTick(tool, wear, mined, ticks), tool.getMaxDamage() - tool.getDamageValue() - 1);
            spend(player, tool, spend);
        }
        effects(player, level, pos, face, ticks, wear, tuning);
        return ticks;
    }

    /**
     * Breaks a pod-only block for a player who has been mining it for {@code mined} ticks: refused for an item that is
     * no tool, for a {@code timed} break (a STOP from the client) that comes more than {@code stopSlackTicks} short of
     * the break time, since vanilla's own check would let it through at 0.7 of it, and for a block a
     * {@code PlayerBlockBreakEvents.BEFORE} listener vetoes (nothing is charged in any of these). A tool that cannot
     * pay what is due is used up and the block still goes. The block goes without a drop, a pulverised block, so brute
     * force is never an ore exploit, but with the events a hand break fires, so a gas pocket still vents and protection
     * mods still veto. Creative players never come here. A refusal sends the client the block back, so it shows no
     * ghost.
     */
    public static boolean breakByHand(ServerPlayer player, ServerLevel level, BlockPos pos, int mined, boolean timed) {
        boolean broken = tryBreak(player, level, pos, mined, timed);
        if (!broken) {
            player.connection.send(new ClientboundBlockUpdatePacket(pos, level.getBlockState(pos)));
        }
        return broken;
    }

    private static boolean tryBreak(ServerPlayer player, ServerLevel level, BlockPos pos, int mined, boolean timed) {
        // Vanilla's own refusals in destroyBlock, which this replaces for a pod-only block: adventure mode and
        // the like.
        if (player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer())) {
            return false;
        }
        ItemStack tool = player.getMainHandItem();
        HandBreak.Wear wear = HandBreak.wearOf(tool);
        if (wear == HandBreak.Wear.NONE) {
            return false;
        }
        HandBreakTuning tuning = LayerTuning.DEFAULT.handBreak();
        if (timed && mined < tuning.breakTicks() - tuning.stopSlackTicks()) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, state, blockEntity)) {
            PlayerBlockBreakEvents.CANCELED.invoker().onBlockBreakCanceled(level, player, pos, state, blockEntity);
            return false;
        }
        int remaining = tool.getMaxDamage() - tool.getDamageValue();
        int due = switch (wear) {
            case SHARE -> Math.min(remaining, durableCost(tool) - scheduled(durableCost(tool), mined));
            case DESTROYED -> remaining;
            case FREE, NONE -> 0;
        };
        BlockState broken = state.getBlock().playerWillDestroy(level, pos, state, player);
        if (!level.removeBlock(pos, false)) {
            return false;
        }
        // Paid only once the block is gone, so a removal that fails never costs a pick. A tool with less than is due is
        // used up by the block, and the block has already gone.
        spend(player, tool, due);
        state.getBlock().destroy(level, pos, broken);
        PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, player, pos, state, blockEntity);
        return true;
    }

    /** What one block costs a durable tool: {@code durableShare} of its maximum durability. */
    private static int durableCost(ItemStack tool) {
        return (int) Math.round(LayerTuning.DEFAULT.handBreak().durableShare() * tool.getMaxDamage());
    }

    /**
     * What the tool pays this tick, the {@code ticks}-th tick of mining after {@code mined}. A durable tool follows an
     * even schedule. Any other tool pays its remaining points but one over the ticks left, so it reaches its last point
     * on the last tick of mining and the break takes it.
     */
    private static int spentThisTick(ItemStack tool, HandBreak.Wear wear, int mined, int ticks) {
        if (wear == HandBreak.Wear.SHARE) {
            return scheduled(durableCost(tool), ticks) - scheduled(durableCost(tool), mined);
        }
        int ticksLeft = LayerTuning.DEFAULT.handBreak().breakTicks() - ticks;
        return Math.max(tool.getMaxDamage() - tool.getDamageValue() - 1, 0) / ticksLeft;
    }

    /**
     * The points of {@code total} spent after {@code ticks} ticks of mining, evenly over the first {@code breakTicks -
     * 1} and all but the last point, which the break takes.
     */
    private static int scheduled(int total, int ticks) {
        int span = LayerTuning.DEFAULT.handBreak().breakTicks() - 1;
        return (int) ((long) Math.max(total - 1, 0) * Math.min(ticks, span) / span);
    }

    /**
     * Damages {@code tool} by {@code amount} directly, and destroys it, as vanilla does, when the damage reaches its
     * maximum.
     */
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
            ServerPlayer player,
            ServerLevel level,
            BlockPos pos,
            Direction face,
            int ticks,
            HandBreak.Wear wear,
            HandBreakTuning tuning) {
        if (ticks % tuning.sparkTicks() == 0) {
            Vec3 at = Vec3.atCenterOf(pos).relative(face, 0.5);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 6, 0.2, 0.2, 0.2, 0.3);
        }
        if (ticks % tuning.soundTicks() == 0) {
            level.playSound(null, pos, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.7F, 0.9F);
        }
        if (ticks % tuning.messageTicks() == 1 && wear != HandBreak.Wear.FREE) {
            player.sendOverlayMessage(Component.translatable(
                    wear == HandBreak.Wear.SHARE
                            ? "deepcharter.layer.pod_only.burning"
                            : "deepcharter.layer.pod_only.destroying"));
        }
    }
}
