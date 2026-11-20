package io.github.pkeppeler.deepcharter.layer;

import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeLayer;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.phys.Vec3;

/**
 * The biome layer of a campaign level, added again after the timelines and the weather (ADR 0062). In one shared dimension type the
 * surface's sky timeline would replace the fog, light and dust that a layer's zone biome sets, because timelines apply after biomes.
 */
public final class LayerAtmosphere {
    private LayerAtmosphere() {}

    /**
     * Adds to {@code builder}, for each {@code visual/*} and {@code audio/*} attribute that a biome sets and that a timeline or the weather
     * also drives, a positional layer that reads the biome as vanilla's own biome layer does. The biome entry is applied a second time, so
     * a biome sets such an attribute with {@code override} (the layers' zone biomes do; {@code CampaignAtmosphereTest} pins it). An attribute
     * nothing drives after the biome is left out: its first biome layer already stands last.
     */
    public static void addBiomeLayers(
            EnvironmentAttributeSystem.Builder builder,
            Level level,
            Map<EnvironmentAttribute<?>, List<EnvironmentAttributeLayer<?>>> layersByAttribute) {
        BiomeManager biomes = level.getBiomeManager();
        level.registryAccess()
                .lookupOrThrow(Registries.BIOME)
                .listElements()
                .flatMap(biome -> biome.value().getAttributes().keySet().stream())
                .distinct()
                .filter(LayerAtmosphere::isVisualOrAudio)
                .filter(attribute -> layersByAttribute.getOrDefault(attribute, List.of()).stream()
                        .anyMatch(EnvironmentAttributeLayer.TimeBased.class::isInstance))
                .forEach(attribute -> addBiomeLayer(builder, attribute, biomes));
    }

    /** True for {@code visual/*} and {@code audio/*}: the attributes a player sees and hears, as against {@code gameplay/*}. */
    public static boolean isVisualOrAudio(EnvironmentAttribute<?> attribute) {
        Identifier id = BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.getKey(attribute);
        return id != null && (id.getPath().startsWith("visual/") || id.getPath().startsWith("audio/"));
    }

    /** Vanilla's {@code EnvironmentAttributeSystem.addBiomeLayerForAttribute}, which is private. */
    private static <Value> void addBiomeLayer(
            EnvironmentAttributeSystem.Builder builder, EnvironmentAttribute<Value> attribute, BiomeManager biomes) {
        builder.addPositionalLayer(attribute, (value, position, interpolator) -> {
            if (interpolator != null && attribute.isSpatiallyInterpolated()) {
                return interpolator.applyAttributeLayer(attribute, value);
            }
            return biomeAt(biomes, attribute, position).getAttributes().applyModifier(attribute, value);
        });
    }

    private static Biome biomeAt(BiomeManager biomes, EnvironmentAttribute<?> attribute, Vec3 position) {
        return (attribute.isFullResolutionBiomes()
                        ? biomes.getBiome(Mth.floor(position.x), Mth.floor(position.y), Mth.floor(position.z))
                        : biomes.getNoiseBiomeAtPosition(position.x, position.y, position.z))
                .value();
    }
}
