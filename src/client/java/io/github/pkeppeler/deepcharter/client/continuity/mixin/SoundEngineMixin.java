package io.github.pkeppeler.deepcharter.client.continuity.mixin;

import io.github.pkeppeler.deepcharter.client.continuity.Silence;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The hard cut's silence (#1337, ADR 0100): while {@link Silence} holds, the engine starts no sound but the Continuity Event's own.
 * Target: the head of {@code SoundEngine.play(SoundInstance)}, which every sound passes through (the sound manager's play, its delayed and
 * ticking queues, music); a refused sound returns {@code NOT_STARTED}, as one the engine has not loaded does, and is not heard by the
 * subtitles or by {@code RecentSounds} either.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Inject(
            method =
                    "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
            at = @At("HEAD"),
            cancellable = true)
    private void deepcharter$silence(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (Silence.refuses(sound)) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }
}
