package io.github.pkeppeler.deepcharter.client.creature;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;

public final class CreatureClientRegistry {
	private CreatureClientRegistry() {
	}

	public static void register() {
		EntityRendererRegistry.register(CreatureRegistry.LAMPLESS_FIGURE, LamplessFigureRenderer::new);
	}
}
