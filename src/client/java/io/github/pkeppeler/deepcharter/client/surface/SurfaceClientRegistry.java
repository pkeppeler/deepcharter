package io.github.pkeppeler.deepcharter.client.surface;

import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;

import net.minecraft.client.particle.SuspendedParticle;

import io.github.pkeppeler.deepcharter.surface.SurfaceRegistry;

public final class SurfaceClientRegistry {
	private SurfaceClientRegistry() {
	}

	public static void register() {
		// The tiny drifting speck of vanilla's underwater particle; the sprite is the pack's.
		ParticleProviderRegistry.getInstance().register(SurfaceRegistry.DUST_MOTE_PARTICLE, SuspendedParticle.UnderwaterProvider::new);
	}
}
