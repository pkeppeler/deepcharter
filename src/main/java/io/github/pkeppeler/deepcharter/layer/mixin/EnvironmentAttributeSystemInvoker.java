package io.github.pkeppeler.deepcharter.layer.mixin;

import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches vanilla's private {@code EnvironmentAttributeSystem.addBiomeLayerForAttribute(Builder, EnvironmentAttribute, BiomeManager)}, so a
 * campaign level adds the very same biome layer again after the timelines (ADR 0062).
 */
@Mixin(EnvironmentAttributeSystem.class)
public interface EnvironmentAttributeSystemInvoker {
    // Java requires a body on a static interface method; Mixin replaces it, and the replacement is the only caller
    // that matters.
    @SuppressWarnings("DoNotCallSuggester")
    @Invoker("addBiomeLayerForAttribute")
    static <Value> void addBiomeLayerForAttribute(
            EnvironmentAttributeSystem.Builder builder, EnvironmentAttribute<Value> attribute, BiomeManager biomes) {
        throw new AssertionError("replaced by Mixin");
    }
}
