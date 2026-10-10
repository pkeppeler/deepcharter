package io.github.pkeppeler.deepcharter.client.creature;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;

public final class CreatureClientRegistry {
	private CreatureClientRegistry() {
	}

	public static void register() {
		EntityRendererRegistry.register(CreatureRegistry.LAMPLESS_FIGURE, CreatureClientRegistry::figureRenderer);
	}

	/** Chosen again on every resource reload, so the dev switch is read then. */
	private static EntityRenderer<LamplessFigure, ?> figureRenderer(EntityRendererProvider.Context context) {
		return FigureConcept.selected().<EntityRenderer<LamplessFigure, ?>>map(concept -> new FigureConceptRenderer(context, concept))
				.orElseGet(() -> new LamplessFigureRenderer(context));
	}
}
