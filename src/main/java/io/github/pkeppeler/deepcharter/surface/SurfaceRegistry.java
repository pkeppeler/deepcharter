package io.github.pkeppeler.deepcharter.surface;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;

import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

public final class SurfaceRegistry {
	/**
	 * The dust that drifts in the surface air. The sky timeline names it in its {@code ambient_particles} track; its look is the
	 * resource files {@code particles/dust_mote.json} and its texture.
	 */
	public static final SimpleParticleType DUST_MOTE_PARTICLE = Registry.register(BuiltInRegistries.PARTICLE_TYPE,
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "dust_mote"), FabricParticleTypes.simple());

	private SurfaceRegistry() {
	}

	/** Loads this class, which registers the particle. The surface rules and structure removal come with #55. */
	public static void register() {
	}
}
