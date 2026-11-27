package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While {@link EvidencePins#cardinalLightNether} is on, every dimension type shades block faces as {@code cardinal_light: nether} does,
 * which is what a test-only type with that setting would give the surface. The shade is a client render value (sections are meshed
 * with it), so a world opened with the switch on is drawn so from its first section. Test code only.
 */
@Mixin(value = CardinalLighting.Type.class, remap = false)
public abstract class CardinalLightingMixin {
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void deepcharter$nether(CallbackInfoReturnable<CardinalLighting> cir) {
        if (EvidencePins.cardinalLightNether) {
            cir.setReturnValue(CardinalLighting.NETHER);
        }
    }
}
