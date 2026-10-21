package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Below layer 1, rock cannot be broken by hand tools, only by pod drills (SPEC section 9). Rock is the
 * {@code deepcharter:deep_rock} block tag, which includes {@code #c:ores}. Creative players are exempt, as they are for the breach crust. Drills
 * remove blocks directly, so this guard never meets them.
 */
public final class LayerRock {
    public static final TagKey<Block> DEEP_ROCK =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "deep_rock"));

    private LayerRock() {}

    public static void init() {
        // Both sides: the client's refusal stops the crack animation; the server's is the backstop.
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                !player.isCreative() && isOutOfReachOfHands(level, pos, level.getBlockState(pos))
                        ? InteractionResult.FAIL
                        : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                player.isCreative() || !isOutOfReachOfHands(level, pos, state));
    }

    /** True for rock in layer 2 and below. */
    private static boolean isOutOfReachOfHands(Level level, BlockPos pos, BlockState state) {
        return state.is(DEEP_ROCK)
                && LayerChain.bandAt(level, pos.getY()).stream().anyMatch(band -> band.layer() > 1);
    }
}
