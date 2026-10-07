package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.world.damagesource.DamageSource;

public final class PodMovement {
	// Filled by #29: server-authoritative movement and rotor flight.

	private PodMovement() {
	}

	public static void init() {
	}

	/** Called every pod tick, on both sides. */
	public static void tick(PodEntity pod) {
	}

	/** Called when the pod lands; this is where #29 decides the hull damage. */
	public static void onLanding(PodEntity pod, double fallDistance, float damageMultiplier, DamageSource source) {
	}
}
