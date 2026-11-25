package io.github.pkeppeler.deepcharter.pod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A ping is a noise the Sleeper's are drawn to (SPEC section 8, #924): every creature in {@link SonarTags#HEARS} within the ping's noise radius
 * is sent, at its own pace, to where the ping came from, and is held there for {@link SonarTuning#lureTicks()}, the ten seconds of silence after
 * which they give up (CREATURES.md). A creature's own goals can send it elsewhere in that time: while the lure holds it is sent again, every
 * {@link #RESEND_TICKS} ticks, whenever it is not already heading for the ping. A new ping from anywhere refreshes the hold. What the creature
 * does on arrival is the creature's.
 *
 * <p>The pathfinder reaches only as far as the creature's follow range, which the lure does not touch: a creature in the tag brings the
 * attribute ({@code PodSonarTest}, over the shipped tag).
 */
final class SonarLure {
    /** The creature's own full walking speed: the navigation's speed modifier is a multiple of its speed attribute. */
    private static final double OWN_PACE = 1.0;
    /** Ticks between two checks that a held creature still heads for the ping. */
    private static final int RESEND_TICKS = 10;
    /** A held creature this close to the ping has arrived; the pod's hull stops it a little short of the middle. */
    private static final double ARRIVED_BLOCKS = 6;

    /** The holds, by entity id (ids are unique across a server's levels). Plain values only, so that no level or entity outlives its server. */
    private static final Map<Integer, Hold> HOLDS = new HashMap<>();

    private record Hold(ResourceKey<Level> dimension, Vec3 target, long until) {}

    private SonarLure() {}

    static void init() {
        SonarEvents.PINGED.register(SonarLure::draw);
        ServerTickEvents.END_SERVER_TICK.register(SonarLure::hold);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> HOLDS.clear());
    }

    private static void draw(SonarPing ping) {
        Vec3 origin = ping.origin();
        double span = 2.0 * ping.noiseBlocks();
        long until = ping.level().getGameTime() + SonarTuning.DEFAULT.lureTicks();
        // entity-query: a creature in an unloaded chunk hears nothing, which is a miss, not a wrong answer: it was not
        // there to be drawn.
        for (Mob mob : ping.level()
                .getEntitiesOfClass(
                        Mob.class,
                        AABB.ofSize(origin, span, span, span),
                        mob -> mob.isAlive() && mob.is(SonarTags.HEARS) && ping.hears(mob.position()))) {
            HOLDS.put(mob.getId(), new Hold(ping.level().dimension(), origin, until));
            send(mob, origin);
        }
    }

    private static void send(Mob mob, Vec3 target) {
        mob.getNavigation().moveTo(target.x, target.y, target.z, OWN_PACE);
    }

    private static void hold(MinecraftServer server) {
        Iterator<Map.Entry<Integer, Hold>> holds = HOLDS.entrySet().iterator();
        while (holds.hasNext()) {
            Map.Entry<Integer, Hold> entry = holds.next();
            Hold hold = entry.getValue();
            ServerLevel level = server.getLevel(hold.dimension());
            // entity-query: the id of a creature the hold already found, read back; one in an unloaded chunk or gone
            // ends its hold
            if (level == null
                    || level.getGameTime() >= hold.until()
                    || !(level.getEntity(entry.getKey()) instanceof Mob mob)
                    || !mob.isAlive()) {
                holds.remove();
            } else if ((hold.until() - level.getGameTime()) % RESEND_TICKS == 0
                    && !arrivedOrHeading(mob, hold.target())) {
                send(mob, hold.target());
            }
        }
    }

    private static boolean arrivedOrHeading(Mob mob, Vec3 target) {
        BlockPos at = BlockPos.containing(target);
        return mob.position().distanceToSqr(target) <= ARRIVED_BLOCKS * ARRIVED_BLOCKS
                || (mob.getNavigation().isInProgress()
                        && Objects.equals(at, mob.getNavigation().getTargetPos()));
    }
}
