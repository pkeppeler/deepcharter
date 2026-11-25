package io.github.pkeppeler.deepcharter.layer.mixin;

import io.github.pkeppeler.deepcharter.layer.HandBreak;
import io.github.pkeppeler.deepcharter.layer.HandBreakCost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The server's half of brute-forcing a pod-only block by hand (ADR 0069). No Fabric event reaches a tick of mining, so
 * the targets are:
 * <ul>
 *   <li>{@code ServerPlayerGameMode.tick()}, at its tail: while the player is mining, the cost, sparks and grinding of
 *       that tick;</li>
 *   <li>{@code handleBlockBreakAction(...)}, at its head: a new swing starts the count again;</li>
 *   <li>{@code destroyBlock(BlockPos)}, at its head, for a pod-only block only: pays the rest of the cost and breaks
 * the       block without a drop. It replaces vanilla's, which would drop the block's loot and damage the tool through
 *       Unbreaking.</li>
 * </ul>
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Shadow
    protected ServerLevel level;

    @Shadow
    @Final
    protected ServerPlayer player;

    @Shadow
    private boolean isDestroyingBlock;

    @Shadow
    private BlockPos destroyPos;

    @Shadow
    private Direction destroyDirection;

    /** Ticks the player has mined the pod-only block of this swing with a tool. */
    @Unique
    private int deepcharter$minedTicks;

    /**
     * The stack held when the count began or last restarted. A different stack, whether another item or a swapped copy,
     * restarts the count, so a client cannot mine with one tool and finish with another.
     */
    @Unique
    private ItemStack deepcharter$countedTool = ItemStack.EMPTY;

    /** True while a STOP from the client is being handled: only that break is held to the break time. */
    @Unique
    private boolean deepcharter$stopping;

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"))
    private void deepcharter$newSwing(
            BlockPos pos,
            ServerboundPlayerActionPacket.Action action,
            Direction face,
            int maxY,
            int sequence,
            CallbackInfo ci) {
        if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            deepcharter$minedTicks = 0;
            deepcharter$countedTool = player.getMainHandItem();
        }
        deepcharter$stopping = action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK;
    }

    @Inject(method = "handleBlockBreakAction", at = @At("RETURN"))
    private void deepcharter$handled(
            BlockPos pos,
            ServerboundPlayerActionPacket.Action action,
            Direction face,
            int maxY,
            int sequence,
            CallbackInfo ci) {
        deepcharter$stopping = false;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void deepcharter$grind(CallbackInfo ci) {
        if (isDestroyingBlock) {
            deepcharter$restartOnNewTool();
            deepcharter$minedTicks =
                    HandBreakCost.grind(player, level, destroyPos, destroyDirection, deepcharter$minedTicks);
        }
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void deepcharter$bruteForce(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (HandBreak.governs(player, level, pos, level.getBlockState(pos))) {
            deepcharter$restartOnNewTool();
            cir.setReturnValue(
                    HandBreakCost.breakByHand(player, level, pos, deepcharter$minedTicks, deepcharter$stopping));
            deepcharter$minedTicks = 0;
        }
    }

    @Unique
    private void deepcharter$restartOnNewTool() {
        ItemStack held = player.getMainHandItem();
        if (held != deepcharter$countedTool) {
            deepcharter$minedTicks = 0;
            deepcharter$countedTool = held;
        }
    }
}
