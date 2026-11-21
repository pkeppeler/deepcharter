package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The one rule for pod-only blocks (ADR 0069): the breach crust, Company rock ({@code deepcharter:undiggable}) and the
 * rock of layer 2 and below ({@code deepcharter:deep_rock}, which includes {@code #c:ores}). A pod drill or a charge
 * removes them directly and never meets this. A hand tool may brute-force one at a ruinous cost ({@link HandBreakCost}):
 * the right pick, 60 s a block, and a share of its durability that grows with depth.
 *
 * <p>This class holds what both sides must agree on, so it runs on the client as well: the predicate, the layer a block
 * counts as, which tools bite, the destroy progress (read by the client's prediction and by the server's check through
 * {@code BlockStateBaseMixin}, so the client never predicts a break the server refuses) and the swing callback. Creative
 * players are exempt from all of it.
 */
public final class HandBreak {
    /** Rock below layer 1 is pod-only; {@code #c:ores} is part of it. */
    public static final TagKey<Block> DEEP_ROCK =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "deep_rock"));

    private static final String FIRST_TRY_KEY = "deepcharter.layer.pod_only.first_try";
    private static final String CANNOT_BITE_KEY = "deepcharter.layer.pod_only.cannot_bite";

    /**
     * Players already told, for this session and not saved: the server forgets one on disconnect. An integrated server
     * shares it with the client in the same JVM, which is harmless: one line either way.
     */
    private static final Set<UUID> TOLD = ConcurrentHashMap.newKeySet();

    private HandBreak() {}

    public static void init() {
        // Both sides: the client's refusal starts no crack for a tool that cannot bite, the server's is the backstop.
        AttackBlockCallback.EVENT.register(HandBreak::onSwing);
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> TOLD.remove(handler.getPlayer().getUUID()));
    }

    /**
     * True for a block a hand cannot break at the speed of a hand: crust, Company rock, and deep rock below layer 1. It
     * reads only the level, so it works on the client.
     */
    public static boolean isPodOnly(Level level, BlockPos pos, BlockState state) {
        if (state.is(LayerBlocks.BREACH_CRUST) || state.is(HazardBlocks.UNDIGGABLE)) {
            return true;
        }
        return state.is(DEEP_ROCK) && layerOf(level, pos) > 1;
    }

    /** True when a swing or a break by this player is governed by the rule: not a creative player, and a pod-only block. */
    public static boolean governs(Player player, Level level, BlockPos pos, BlockState state) {
        return !player.isCreative() && isPodOnly(level, pos, state);
    }

    /**
     * The layer a block counts as, which sets what bites it and what it costs. The crust counts as the layer whose floor it
     * is. Unbuilt depth of the campaign world counts as the layer below the last one built; the surface and a level
     * outside the chain count as layer 1.
     */
    public static int layerOf(Level level, BlockPos pos) {
        return LayerChain.layerAt(level, pos.getY())
                .map(Band::layer)
                .orElseGet(() -> unbuiltDepth(level, pos) ? CampaignBands.lastLayer() + 1 : 1);
    }

    private static boolean unbuiltDepth(Level level, BlockPos pos) {
        return LayerChain.isCampaign(level)
                && pos.getY() < CampaignBands.layer(CampaignBands.lastLayer()).minY();
    }

    /** True when {@code tool} is a tool that bites a pod-only block of {@code layer}. */
    public static boolean bites(ItemStack tool, int layer) {
        return tool.isDamageableItem()
                && tool.is(LayerTuning.DEFAULT.handBreak().toolsFor(layer));
    }

    /**
     * The destroy progress of a pod-only block for a player under the rule: one tick's share of {@code breakTicks}
     * whatever the enchantments, effects or footing, for a tool that bites, and none for any other.
     */
    public static float progress(Player player, Level level, BlockPos pos) {
        return bites(player.getMainHandItem(), layerOf(level, pos))
                ? 1.0F / LayerTuning.DEFAULT.handBreak().breakTicks()
                : 0.0F;
    }

    private static InteractionResult onSwing(
            Player player, Level level, InteractionHand hand, BlockPos pos, Direction direction) {
        if (!governs(player, level, pos, level.getBlockState(pos))) {
            return InteractionResult.PASS;
        }
        boolean bites = bites(player.getMainHandItem(), layerOf(level, pos));
        // A client that refuses a swing never tells the server, so it tells the player itself. A tool that bites goes
        // on
        // to the server, which tells.
        if (!bites || !level.isClientSide()) {
            tellOnce(player, bites);
        }
        return bites ? InteractionResult.PASS : InteractionResult.FAIL;
    }

    private static void tellOnce(Player player, boolean bites) {
        if (TOLD.add(player.getUUID())) {
            player.sendSystemMessage(Component.translatable(bites ? FIRST_TRY_KEY : CANNOT_BITE_KEY));
        }
    }
}
