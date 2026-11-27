package io.github.pkeppeler.deepcharter.layer.mixin;

import io.github.pkeppeler.deepcharter.layer.LayerAtmosphere;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import java.util.List;
import java.util.Map;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeLayer;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the biome layer again after the timelines and the weather, in a campaign level (ADR 0062). Target:
 * {@code EnvironmentAttributeSystem.Builder.addDefaultLayers(Level)}, at its return, after the static layers (type, then biome) and the
 * dynamic ones (timelines, then weather) are in.
 */
@Mixin(EnvironmentAttributeSystem.Builder.class)
public abstract class EnvironmentAttributeSystemBuilderMixin {
    @Shadow
    @Final
    private Map<EnvironmentAttribute<?>, List<EnvironmentAttributeLayer<?>>> layersByAttribute;

    @Inject(method = "addDefaultLayers", at = @At("RETURN"))
    private void deepcharter$biomesAfterTimelines(
            Level level, CallbackInfoReturnable<EnvironmentAttributeSystem.Builder> cir) {
        if (LayerChain.isCampaign(level)) {
            LayerAtmosphere.addBiomeLayers(cir.getReturnValue(), level, layersByAttribute);
        }
    }
}
