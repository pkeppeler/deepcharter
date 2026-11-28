package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;

/**
 * Landing damage reads sink speed, not distance fallen (#319); a seated rider is shielded and the hull takes the hit.
 * The rules are in mechanics.md, Hard landings.
 */
public final class HardLanding {
    private HardLanding() {}

    public static void init() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(HardLanding::allowDamage);
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
     * The sink speed a pod lands at after falling {@code blocks} from rest with no rotor held: {@link PodMovement}'s
     * gravity and drag, tick by tick, until a tick's move reaches the floor. That tick's speed is the one {@link
     * #onLanding} reads, since vanilla calls it before the move's speed is cleared. A fall that ends exactly on the
     * floor's top lands a tick later, so the count goes one tick past it.
     */
    public static double freeFallSpeed(double blocks) {
        if (blocks <= 0) {
            return 0;
        }
        PodTuning.Movement tuning = PodTuning.DEFAULT.movement();
        double velocity = 0;
        double fallen = 0;
        while (true) {
            velocity = tuning.fall(velocity, tuning.gravity());
            fallen -= velocity;
            if (fallen > blocks) {
                return sinkSpeed(velocity);
            }
        }
    }

    /** Hull points a pod with these stats loses dropping {@code blocks} from rest onto plain ground, with no rotor held. */
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
            PodCues.hurtHull(pod, damage, "deepcharter.pod.hard_landing", DeepSound.POD_CRASH, ParticleTypes.POOF, 16);
        }
    }

    private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        return !(source.is(DamageTypes.FALL) && entity.getVehicle() instanceof PodEntity pod && !Wrecks.isWreck(pod));
    }
}
