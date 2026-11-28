package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Landing damage reads sink speed, not distance fallen (#319), and the rotor's ground cushion (#1432); a seated rider
 * is shielded and the hull takes the hit. The rules are in mechanics.md, Hard landings and Soft landings.
 */
public final class HardLanding {
    private HardLanding() {}

    public static void init() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(HardLanding::allowDamage);
        PodEvents.AFTER_TICK.register(HardLanding::cushion);
    }

    /** Sink speed in blocks per tick: positive when moving down, 0 when rising. */
    public static double sinkSpeed(double verticalVelocity) {
        return Math.max(0, -verticalVelocity);
    }

    /** Whether landing at this sink speed damages a pod with these stats. */
    public static boolean isHard(PodStats stats, double sinkSpeed) {
        return sinkSpeed > stats.hardLandingSpeed();
    }

    /** Hull points lost landing at {@code sinkSpeed}, 0 at or under the threshold. */
    public static float hullDamage(PodStats stats, double sinkSpeed, float damageMultiplier) {
        return isHard(stats, sinkSpeed)
                ? (float) ((sinkSpeed - stats.hardLandingSpeed()) * stats.hullDamagePerSpeed() * damageMultiplier)
                : 0f;
    }

    /**
     * The sink speed a seated, powered pod lands at after falling {@code blocks} from rest with no rotor held: {@link PodMovement}'s gravity and
     * drag, tick by tick, and the ground cushion's brake once ground is within {@link HardLandingTuning#cushionHeight}, until a tick's move reaches the
     * floor. That tick's speed is the one {@link #onLanding} reads, since vanilla calls it before the move's speed is cleared. A fall that ends
     * exactly on the floor's top lands a tick later, so the count goes one tick past it.
     */
    public static double freeFallSpeed(double blocks) {
        if (blocks <= 0) {
            return 0;
        }
        PodTuning.Movement tuning = PodTuning.DEFAULT.movement();
        HardLandingTuning cushion = HardLandingTuning.DEFAULT;
        double velocity = 0;
        double fallen = 0;
        while (true) {
            velocity = tuning.fall(velocity, tuning.gravity());
            fallen -= velocity;
            if (fallen > blocks) {
                return sinkSpeed(velocity);
            }
            double sink = sinkSpeed(velocity);
            if (sink > cushion.cushionSink() && blocks - fallen < cushion.cushionHeight()) {
                velocity = -Math.max(cushion.cushionSink(), sink - cushion.cushionBrake());
            }
        }
    }

    /** Hull points a pod with these stats loses dropping {@code blocks} from rest onto plain ground, seated and powered, with no rotor held. */
    public static float freeFallDamage(PodStats stats, double blocks) {
        return hullDamage(stats, freeFallSpeed(blocks), 1f);
    }

    /** Called by vanilla when the pod lands, with the speed it ran into still in its motion. */
    static void onLanding(PodEntity pod, float damageMultiplier) {
        if (pod.level().isClientSide()) {
            return;
        }
        float damage = hullDamage(PodStats.of(pod), sinkSpeed(pod.getDeltaMovement().y), damageMultiplier);
        if (damage > 0f) {
            // Loose ore in a rider's pack rattles: a hard landing costs the hull more for each ore, never by chance.
            damage += PodLooseOre.rattle(pod) * damageMultiplier;
            PodCues.hurtHull(pod, damage, "deepcharter.pod.hard_landing", DeepSound.POD_CRASH, ParticleTypes.POOF, 16);
        }
    }

    /** Brakes a crewed, powered pod's sink while ground is within {@link HardLandingTuning#cushionHeight} below. */
    private static void cushion(PodEntity pod) {
        HardLandingTuning tuning = HardLandingTuning.DEFAULT;
        Vec3 motion = pod.getDeltaMovement();
        double sink = sinkSpeed(motion.y);
        if (sink <= tuning.cushionSink()
                || pod.noPhysics
                || pod.getControllingPassenger() == null
                || !PodEvents.isPowered(pod)
                || !(pod.level() instanceof ServerLevel level)) {
            return;
        }
        AABB hull = pod.getBoundingBox();
        AABB below =
                new AABB(hull.minX, hull.minY - tuning.cushionHeight(), hull.minZ, hull.maxX, hull.minY, hull.maxZ);
        if (!level.getBlockCollisions(pod, below).iterator().hasNext()) {
            return;
        }
        pod.setDeltaMovement(motion.x, -Math.max(tuning.cushionSink(), sink - tuning.cushionBrake()), motion.z);
        level.sendParticles(
                ParticleTypes.CLOUD,
                pod.getX(),
                hull.minY,
                pod.getZ(),
                4,
                hull.getXsize() / 3,
                0.1,
                hull.getZsize() / 3,
                0.02);
    }

    private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        return !(source.is(DamageTypes.FALL) && entity.getVehicle() instanceof PodEntity pod && !Wrecks.isWreck(pod));
    }
}
