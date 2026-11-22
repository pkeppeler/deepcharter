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
    /** True when something at {@code listener} in the pod's level hears this ping: within its noise radius. */
    // test-seam: the call #924's creatures make to hear a ping; nothing in this PR listens yet, and the tests pin the
    // radius through it
    public boolean hears(Vec3 listener) {
        return origin.distanceToSqr(listener) <= (double) noiseBlocks * noiseBlocks;
    }
}
