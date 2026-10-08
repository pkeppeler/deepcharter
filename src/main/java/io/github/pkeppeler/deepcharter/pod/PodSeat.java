package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.world.entity.Entity;

/**
 * Where a rider sits in a pod, which is the order they boarded: the first aboard is the pilot, who drives, and the second is the
 * navigator, who sees the scanner and nothing else (SPEC section 8; sonar comes in M3). When the pilot gets off, the navigator is
 * the first aboard, and is the pilot.
 */
public enum PodSeat {
	PILOT,
	NAVIGATOR;

	/** The seat of {@code rider} in {@code pod}. A rider who is not aboard is a bug in the caller, so it throws. */
	public static PodSeat of(PodEntity pod, Entity rider) {
		int index = pod.getPassengers().indexOf(rider);
		if (index < 0) {
			throw new IllegalArgumentException(rider + " is not riding pod " + pod.getUUID());
		}
		return index == 0 ? PILOT : NAVIGATOR;
	}

	/** True when the seat shows the pod's hull, fuel, cargo and depth, and sounds its low-fuel beep. */
	public boolean showsPodStatus() {
		return this == PILOT;
	}
}
