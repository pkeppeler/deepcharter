package io.github.pkeppeler.deepcharter.surface.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;

/**
 * No dimension is a portal dimension, so a fire placed in an obsidian frame never makes a nether portal
 * (SPEC section 3). Flint and steel, fire charges, dispensers and spreading fire all end in
 * {@code BaseFireBlock.onPlace}, which asks this method first. A portal block placed by a command still works.
 */
@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockMixin {
	@Inject(method = "inPortalDimension", at = @At("HEAD"), cancellable = true)
	private static void deepcharter$noPortalDimension(Level level, CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(false);
	}
}
