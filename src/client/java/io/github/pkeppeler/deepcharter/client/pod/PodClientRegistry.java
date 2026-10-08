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
		EntityRendererRegistry.register(PodRegistry.POD, context -> new PodRenderer(context, Chassis.MOLE));
		EntityRendererRegistry.register(PodRegistry.PROSPECTOR, context -> new PodRenderer(context, Chassis.PROSPECTOR));
		// The motion of the end rod it replaced; the sprite is the pack's.
		ParticleProviderRegistry.getInstance().register(PodRegistry.TOW_CABLE_PARTICLE, EndRodParticle.Provider::new);
	}
}
