package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Makes the attack button read as held while {@link EvidencePins#attackHeld} is on, so the client mines as it does for
 * a player who holds the button. Target: {@code Minecraft.continueAttack(boolean)}, whose argument is the button's
 * state: false aborts the block being mined on every tick, and the test client's mouse is never grabbed. Test code
 * only.
 */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MinecraftAttackMixin {
    @ModifyVariable(method = "continueAttack", at = @At("HEAD"), argsOnly = true)
    private boolean deepcharter$attackHeld(boolean down) {
        return down || EvidencePins.attackHeld;
    }
}
