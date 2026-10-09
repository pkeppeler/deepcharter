package io.github.pkeppeler.deepcharter.client.pod;

import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import net.minecraft.client.particle.EndRodParticle;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

public final class PodClientRegistry {
	private PodClientRegistry() {
	}

	public static void register() {
		// A bad dev switch fails here, at startup, not later on a resource reload's crash screen.
		PodConcept.selected();
		EntityRendererRegistry.register(PodRegistry.POD, PodClientRegistry::mole);
		EntityRendererRegistry.register(PodRegistry.PROSPECTOR, context -> new PodRenderer(context, Chassis.PROSPECTOR));
		// The motion of the end rod it replaced; the sprite is the pack's.
		ParticleProviderRegistry.getInstance().register(PodRegistry.TOW_CABLE_PARTICLE, EndRodParticle.Provider::new);
	}

	/** The Mole's shipping look, or a #334 concept when the dev switch names one. Asked again on every resource reload. */
	private static EntityRenderer<PodEntity, ?> mole(EntityRendererProvider.Context context) {
		return PodConcept.selected().<EntityRenderer<PodEntity, ?>>map(concept -> new PodGeoRenderer(context, concept))
				.orElseGet(() -> new PodRenderer(context, Chassis.MOLE));
	}
}
