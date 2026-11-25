package io.github.pkeppeler.deepcharter.pod;

import java.util.Optional;
import net.minecraft.world.entity.Entity;

/**
 * Where a rider sits in a pod, which is the order they boarded: the first aboard is the pilot, who drives, and the second is the
 * navigator, who sees the scanner and works the sonar ({@link PodSonar}). When the pilot gets off, the navigator is
 * the first aboard, and is the pilot.
 */
public enum PodSeat {
    PILOT,
    NAVIGATOR;

    /**
     * The seat of {@code rider} in {@code pod}, or empty when the rider holds no seat. The seat comes from the server's assignment ({@link PodSeats}), so
     * a client that has not loaded another rider still gives each rider its role. A rider the assignment has not reached yet (a tick after boarding)
     * takes the next seat after the listed ones, so it is empty only for an entity that is not aboard: a render or tick path treats that as no
     * readout and never throws.
     */
    public static Optional<PodSeat> find(PodEntity pod, Entity rider) {
        int index = PodSeats.order(pod).indexOf(rider.getUUID());
        if (index < 0) {
            return Optional.empty();
        }
        return Optional.of(index == 0 ? PILOT : NAVIGATOR);
    }

    /** True when {@code rider} is the navigator, who works the sonar. */
    public static boolean isNavigator(PodEntity pod, Entity rider) {
        return find(pod, rider).filter(seat -> seat == NAVIGATOR).isPresent();
    }

    /** True when {@code rider} is the pilot, who sees the pod's hull, fuel, cargo and depth and hears its low-fuel beep. */
    public static boolean showsPodStatus(PodEntity pod, Entity rider) {
        return find(pod, rider).filter(seat -> seat == PILOT).isPresent();
    }
}
