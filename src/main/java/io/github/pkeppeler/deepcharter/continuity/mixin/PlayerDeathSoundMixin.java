package io.github.pkeppeler.deepcharter.continuity.mixin;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A person dies without vanilla's death cry (#1337, ADR 0100): the crew hear the lamp go out instead ({@code ContinuityEvents.died}).
 * Target: {@code Player.getDeathSound()}, which returns null here; vanilla's {@code makeSound} and {@code handleEntityEvent} already skip a
 * null sound, as for a silent mob.
 */
@Mixin(Player.class)
public abstract class PlayerDeathSoundMixin {
    @Inject(method = "getDeathSound()Lnet/minecraft/sounds/SoundEvent;", at = @At("HEAD"), cancellable = true)
    private void deepcharter$noDeathCry(CallbackInfoReturnable<@Nullable SoundEvent> cir) {
        cir.setReturnValue(null);
    }
}
