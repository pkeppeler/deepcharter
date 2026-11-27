package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.layer.mixin.EnvironmentAttributeSystemInvoker;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeLayer;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.modifier.AttributeModifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * The biome layer of a campaign level, added again after the timelines and the weather (ADR 0062). In one shared dimension type the
 * surface's sky timeline would replace the fog, light and dust that a layer's zone biome sets, because timelines apply after biomes.
 */
public final class LayerAtmosphere {
    private LayerAtmosphere() {}

    /**
     * Adds to {@code builder}, for each {@code visual/*} and {@code audio/*} attribute that a biome sets and that a timeline or the weather
     * also drives, vanilla's own biome layer (its private {@code addBiomeLayerForAttribute}, through
     * {@link EnvironmentAttributeSystemInvoker}). The biome entry is applied a second time, so every biome sets such an attribute with
     * {@code override}; any other modifier would apply twice, so this throws, naming the biome and the attribute. Only the biomes the level's
     * generator can place are checked: the registry holds every vanilla biome, which a campaign level never reads. An attribute nothing
     * drives after the biome is left out: its first biome layer already stands last.
     */
    public static void addBiomeLayers(
            EnvironmentAttributeSystem.Builder builder,
            Level level,
            Map<EnvironmentAttribute<?>, List<EnvironmentAttributeLayer<?>>> layersByAttribute) {
        List<EnvironmentAttribute<?>> readded = level.registryAccess()
                .lookupOrThrow(Registries.BIOME)
                .listElements()
                .flatMap(biome -> biome.value().getAttributes().keySet().stream())
                .distinct()
                .filter(LayerAtmosphere::isVisualOrAudio)
                .filter(attribute -> layersByAttribute.getOrDefault(attribute, List.of()).stream()
                        .anyMatch(EnvironmentAttributeLayer.TimeBased.class::isInstance))
                .toList();
        // The server holds the level's generator; a client reads the same registry the server already checked.
        if (level instanceof ServerLevel server) {
            server.getChunkSource()
                    .getGenerator()
                    .getBiomeSource()
                    .possibleBiomes()
                    .forEach(biome -> {
                        for (EnvironmentAttribute<?> attribute : readded) {
                            requireOverride(
                                    biome.unwrapKey().orElseThrow().identifier(),
                                    biome.value().getAttributes(),
                                    attribute);
                        }
                    });
        }
        BiomeManager biomes = level.getBiomeManager();
        for (EnvironmentAttribute<?> attribute : readded) {
            EnvironmentAttributeSystemInvoker.addBiomeLayerForAttribute(builder, attribute, biomes);
        }
    }

    /** Fails, naming the biome and the attribute, when {@code attributes} sets {@code attribute} with a modifier other than override. */
    public static void requireOverride(
            Identifier biome, EnvironmentAttributeMap attributes, EnvironmentAttribute<?> attribute) {
        var entry = attributes.get(attribute);
        if (entry != null && !entry.modifier().equals(AttributeModifier.override())) {
            throw new IllegalStateException(
                    "Biome " + biome + " sets " + BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.getKey(attribute)
                            + " with a modifier other than override, but a campaign level applies the biome layer twice (ADR 0062)");
        }
    }

    /** True for {@code visual/*} and {@code audio/*}: the attributes a player sees and hears, as against {@code gameplay/*}. */
    public static boolean isVisualOrAudio(EnvironmentAttribute<?> attribute) {
        Identifier id = BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.getKey(attribute);
        return id != null && (id.getPath().startsWith("visual/") || id.getPath().startsWith("audio/"));
    }
}
