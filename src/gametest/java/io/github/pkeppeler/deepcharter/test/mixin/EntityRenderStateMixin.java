package io.github.pkeppeler.deepcharter.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;

/**
 * While {@link EvidencePins#entitiesPosed} is on, every entity but a pod is drawn at rest. The idle sway of the arms reads the age of
 * the entity and the walk cycle reads its steps, so a lampless figure, or the player in the inventory screen, would stand a little
 * differently in each shot. Pods keep their own animation. Test code only.
 */
@Mixin(value = EntityRenderer.class, remap = false)
public abstract class EntityRenderStateMixin {
	@Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("RETURN"))
	private void deepcharter$pose(Entity entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir) {
		if (!EvidencePins.entitiesPosed || entity instanceof PodEntity) {
			return;
		}
		EntityRenderState state = cir.getReturnValue();
		state.ageInTicks = 0f;
		if (state instanceof LivingEntityRenderState living) {
			living.walkAnimationPos = 0f;
			living.walkAnimationSpeed = 0f;
		}
	}
}
