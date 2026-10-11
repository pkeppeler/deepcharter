package io.github.pkeppeler.deepcharter.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.pkeppeler.deepcharter.test.support.EvidencePins;

/**
 * While {@link EvidencePins#spritesFrozen} is on, an animated texture (lava, water, fire) is held on its first frame. Test code only:
 * the gametest mod never ships. If a Minecraft update renames the target, the mixin fails to apply and the client run stops at launch.
 */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimationState", remap = false)
public abstract class SpriteAnimationStateMixin {
	@Shadow
	private int frame;
	@Shadow
	private int subFrame;
	@Shadow
	private boolean isDirty;

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void deepcharter$holdTheFirstFrame(CallbackInfo ci) {
		if (!EvidencePins.spritesFrozen) {
			return;
		}
		// Dirty once, on the tick the sprite goes back to its first frame, so that the atlas redraws it.
		boolean moved = frame != 0 || subFrame != 0;
		frame = 0;
		subFrame = 0;
		isDirty = moved;
		ci.cancel();
	}
}
