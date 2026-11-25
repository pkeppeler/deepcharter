package io.github.pkeppeler.deepcharter.continuity.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.pkeppeler.deepcharter.continuity.ContinuityEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Continuity Event's two hooks into a person's death (#1337, ADR 0100). Target: {@code ServerPlayer.die(DamageSource)}. At its head
 * the event is counted and the dying client told its number, before vanilla sends the combat-kill packet; the message vanilla reads from
 * {@code CombatTracker.getDeathMessage()} becomes the Continuity Event's line, which vanilla then sends to the dying client and to chat
 * under the {@code showDeathMessages} game rule and the team's visibility, as it would its own. The rest of the death runs unchanged.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDieMixin {
    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V", at = @At("HEAD"))
    private void deepcharter$countTheEvent(DamageSource source, CallbackInfo ci) {
        ContinuityEvents.died((ServerPlayer) (Object) this);
    }

    @ModifyExpressionValue(
            method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/damagesource/CombatTracker;getDeathMessage()Lnet/minecraft/network/chat/Component;"))
    private Component deepcharter$continuityLine(Component vanilla, @Local(argsOnly = true) DamageSource source) {
        return ContinuityEvents.message((ServerPlayer) (Object) this, source);
    }
}
