package io.github.pkeppeler.deepcharter.client.creature;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public final class CreatureClientRegistry {
    private CreatureClientRegistry() {}

    // Fabric deprecated EntityRendererRegistry; vanilla EntityRenderers.register is private
    @SuppressWarnings("deprecation")
    public static void register() {
        EntityRendererRegistry.register(CreatureRegistry.LAMPLESS_FIGURE, LamplessFigureRenderer::new);
    }
}
