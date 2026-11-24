package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.SwapProbes;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link SwapProbes} count the stops of every sound ({@code SoundManager.stop()V}, which vanilla runs when it
 * swaps the client's level), to show a swap leaves the sounds playing. Inert unless a test arms the probes. Test code
 * only.
 */
@Mixin(value = SoundManager.class, remap = false)
public abstract class SoundManagerStopProbeMixin {
    @Inject(method = "stop()V", at = @At("HEAD"))
    private void deepcharter$countStop(CallbackInfo ci) {
        SwapProbes.recordSoundStop();
    }
}
