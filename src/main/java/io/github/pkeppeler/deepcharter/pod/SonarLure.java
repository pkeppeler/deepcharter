package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A ping is a noise the Sleeper's are drawn to (SPEC section 8, #924): every creature in {@link SonarTags#HEARS} within the ping's noise radius
 * sets out, at its own pace, to the pod. It listens to {@link SonarEvents#PINGED}. It sets the creature's navigation once; what the creature
 * does on arrival, and whether its own goals let it keep walking, is the creature's.
 */
final class SonarLure {
    /** The creature's own full walking speed: the navigation's speed modifier is a multiple of its speed attribute. */
    private static final double OWN_PACE = 1.0;

    private SonarLure() {}

    static void init() {
        SonarEvents.PINGED.register(SonarLure::draw);
    }

    private static void draw(SonarPing ping) {
        Vec3 origin = ping.origin();
        double span = 2.0 * ping.noiseBlocks();
        // entity-query: a creature in an unloaded chunk hears nothing, which is a miss, not a wrong answer: it was not
        // there to be drawn.
        for (Mob mob : ping.level()
                .getEntitiesOfClass(
                        Mob.class,
                        AABB.ofSize(origin, span, span, span),
                        mob -> mob.isAlive() && mob.is(SonarTags.HEARS) && ping.hears(mob.position()))) {
            mob.getNavigation().moveTo(origin.x, origin.y, origin.z, OWN_PACE);
        }
    }
}
