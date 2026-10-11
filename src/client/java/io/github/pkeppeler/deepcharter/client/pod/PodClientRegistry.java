package io.github.pkeppeler.deepcharter.client.pod;

import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import net.minecraft.client.particle.EndRodParticle;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.TowTuning;

public final class PodClientRegistry {
	private PodClientRegistry() {
	}

	public static void register() {
		// Every chassis draws with GeckoLib from its look file, so a new chassis is files only. The renderer is built again on every resource reload.
		for (Chassis chassis : Chassis.all()) {
			EntityRendererRegistry.register(PodRegistry.typeOf(chassis), context -> new PodGeoRenderer(context, chassis));
		}
		// The motion of the end rod it replaced; the sprite is the pack's. It lives for the interval between two draws of the cable and a little
		// over, not the end rod's three seconds: a cable that swings leaves no trail of old lines.
		ParticleProviderRegistry.getInstance().register(PodRegistry.TOW_CABLE_PARTICLE, sprites -> {
			EndRodParticle.Provider provider = new EndRodParticle.Provider(sprites);
			return (options, level, x, y, z, xd, yd, zd, random) -> {
				var particle = provider.createParticle(options, level, x, y, z, xd, yd, zd, random);
				particle.setLifetime(TowTuning.DEFAULT.cableLifetime());
				particle.scale(TowTuning.DEFAULT.cableScale());
				return particle;
			};
		});
	}
}
