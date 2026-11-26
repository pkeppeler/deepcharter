package io.github.pkeppeler.deepcharter.pod.mixin;

import io.github.pkeppeler.deepcharter.pod.PodDump;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player seated in a pod picks up no ore and nothing a pod dumped (ADR 0103). */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void deepcharter$nothingDumpedIntoASeatedPack(Player player, CallbackInfo ci) {
        if (PodDump.keepsFromPack(player, (ItemEntity) (Object) this)) {
            ci.cancel();
        }
    }
}
