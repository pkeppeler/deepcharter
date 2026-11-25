package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * One ping, as listeners see it: where it came from, how far it sees and how far it is heard. Server only.
 *
 * @param pod         the pod that pinged
 * @param level       the pod's level
 * @param origin      the pod's position when it pinged
 * @param rangeBlocks how far it returns echoes
 * @param noiseBlocks how far it is heard
 */
public record SonarPing(PodEntity pod, ServerLevel level, Vec3 origin, int rangeBlocks, int noiseBlocks) {
    /** Whether a listener at {@code at} hears the ping: within {@link #noiseBlocks} of where it came from, in a straight line through rock. */
    public boolean hears(Vec3 at) {
        return origin.distanceToSqr(at) <= (double) noiseBlocks * noiseBlocks;
    }
}
