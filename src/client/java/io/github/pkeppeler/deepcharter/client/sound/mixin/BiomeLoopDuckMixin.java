package io.github.pkeppeler.deepcharter.client.sound.mixin;

import io.github.pkeppeler.deepcharter.client.sound.DreadBed;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.BiomeAmbientSoundsHandler;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The layer's drone ducks under the Retained's dread bed (#1450, ADR 0109). Target: the tail of
 * {@code BiomeAmbientSoundsHandler.LoopSoundInstance.tick()}, which sets the loop's volume from its own crossfade each tick; the drone's
 * volume is then that times {@link DreadBed#droneGain}, so the duck follows the bed's fade and lets go when it ends. Every biome's
 * {@code ambient_sounds} loop is such an instance (ADR 0057: the drone is biome data, played by vanilla), and nothing else is.
 */
@Mixin(BiomeAmbientSoundsHandler.LoopSoundInstance.class)
public abstract class BiomeLoopDuckMixin extends AbstractTickableSoundInstance {
    protected BiomeLoopDuckMixin(SoundEvent event, SoundSource source, RandomSource random) {
        super(event, source, random);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void deepcharter$duck(CallbackInfo ci) {
        volume *= DreadBed.droneGain();
    }
}
