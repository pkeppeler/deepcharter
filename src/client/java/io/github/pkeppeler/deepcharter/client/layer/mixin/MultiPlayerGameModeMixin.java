package io.github.pkeppeler.deepcharter.client.layer.mixin;

import io.github.pkeppeler.deepcharter.layer.HandBreak;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the client go on mining a pod-only block while the server wears its pick (ADR 0069). Target:
 * {@code MultiPlayerGameMode.sameDestroyTarget(BlockPos)}, at its return. Vanilla takes a held stack that differs in
 * any component, damage included, for a new tool and starts the block again, so each point of wear the server sends
 * would reset the crack to nothing. No Fabric event reaches it.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private BlockPos destroyBlockPos;

    @Shadow
    private ItemStack destroyingItem;

    @Inject(method = "sameDestroyTarget", at = @At("RETURN"), cancellable = true)
    private void deepcharter$wearIsNotANewTool(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (!cir.getReturnValue()
                && player != null
                && level != null
                && pos.equals(destroyBlockPos)
                && HandBreak.governs(player, level, pos, level.getBlockState(pos))
                && HandBreak.sameButWorn(destroyingItem, player.getMainHandItem())) {
            cir.setReturnValue(true);
        }
    }
}
