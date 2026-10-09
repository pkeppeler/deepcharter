package io.github.pkeppeler.deepcharter.client.pod;

import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import net.minecraft.client.particle.EndRodParticle;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

public final class PodClientRegistry {
	private PodClientRegistry() {
	}

	public static void register() {
		// Every chassis draws with GeckoLib from its look file, so a new chassis is files only. The renderer is built again on every resource reload.
		for (Chassis chassis : Chassis.all()) {
			EntityRendererRegistry.register(PodRegistry.typeOf(chassis), context -> new PodGeoRenderer(context, chassis));
		}
		// The motion of the end rod it replaced; the sprite is the pack's.
		ParticleProviderRegistry.getInstance().register(PodRegistry.TOW_CABLE_PARTICLE, EndRodParticle.Provider::new);
	}
}
