package io.github.pkeppeler.deepcharter.pod;

import java.util.Optional;

import net.minecraft.world.entity.Entity;

/**
 * Where a rider sits in a pod, which is the order they boarded: the first aboard is the pilot, who drives, and the second is the
 * navigator, who sees the scanner and nothing else (SPEC section 8; sonar comes in M3). When the pilot gets off, the navigator is
 * the first aboard, and is the pilot.
 */
public enum PodSeat {
	PILOT,
	NAVIGATOR;

	/**
	 * The seat of {@code rider} in {@code pod}, or empty when the rider is not in its passenger list. That happens on a client for
	 * a tick or two while the pod and its passengers sync, so a render or tick path treats it as no readout and never throws.
	 */
	public static Optional<PodSeat> find(PodEntity pod, Entity rider) {
		int index = pod.getPassengers().indexOf(rider);
		if (index < 0) {
			return Optional.empty();
		}
		return Optional.of(index == 0 ? PILOT : NAVIGATOR);
	}

	/** True when the seat shows the pod's hull, fuel, cargo and depth, and sounds its low-fuel beep. */
	public boolean showsPodStatus() {
		return this == PILOT;
	}
}
