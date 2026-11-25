package io.github.pkeppeler.deepcharter.layer.mixin;

import io.github.pkeppeler.deepcharter.layer.HandBreak;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives a pod-only block the same destroy progress on the client and the server (ADR 0069). Target:
 * {@code BlockBehaviour.BlockStateBase.getDestroyProgress(Player, BlockGetter, BlockPos)}, at its head. It is the one
 * method both the client's prediction ({@code MultiPlayerGameMode.continueDestroyBlock}) and the server's check
 * ({@code ServerPlayerGameMode}) read, and it runs the same code on both sides, so the client never predicts a break
 * the server refuses.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
    @Inject(method = "getDestroyProgress", at = @At("HEAD"), cancellable = true)
    private void deepcharter$podOnlyProgress(
            Player player, BlockGetter getter, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        // The only subclass of BlockStateBase is BlockState.
        BlockState state = (BlockState) (Object) this;
        if (getter instanceof Level level && HandBreak.governs(player, level, pos, state)) {
            cir.setReturnValue(HandBreak.progress(player));
        }
    }
}
