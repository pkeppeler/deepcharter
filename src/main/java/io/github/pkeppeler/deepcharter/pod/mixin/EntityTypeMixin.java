package io.github.pkeppeler.deepcharter.pod.mixin;

import io.github.pkeppeler.deepcharter.pod.PodRenames;
import java.util.Optional;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reads a saved entity under the id of a renamed pod type as the pod's current type, before vanilla drops it as unknown (ADR 0045). */
@Mixin(EntityType.class)
public abstract class EntityTypeMixin {
    @Inject(method = "by", at = @At("HEAD"), cancellable = true)
    private static void deepcharter$renamedPod(ValueInput input, CallbackInfoReturnable<Optional<EntityType<?>>> cir) {
        PodRenames.current(input).ifPresent(type -> cir.setReturnValue(Optional.of(type)));
    }
}
