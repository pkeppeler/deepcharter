package io.github.pkeppeler.deepcharter.pod;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;

import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/** Landing damage reads sink speed, not distance fallen (#319), so a rotor-braked descent is safe; a seated rider is shielded and the hull takes the hit. */
public final class HardLanding {
	private HardLanding() {
	}

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
		return isHard(stats, sinkSpeed) ? (float) ((sinkSpeed - stats.hardLandingSpeed()) * stats.hullDamagePerSpeed() * damageMultiplier) : 0f;
	}

	/** Called by vanilla when the pod lands, with the speed it ran into still in its motion. */
	static void onLanding(PodEntity pod, float damageMultiplier) {
		if (pod.level().isClientSide()) {
			return;
		}
		float damage = hullDamage(PodStats.of(pod), sinkSpeed(pod.getDeltaMovement().y), damageMultiplier);
		if (damage > 0f) {
			pod.damageHull(damage);
		}
	}

	private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		return !(source.is(DamageTypes.FALL) && entity.getVehicle() instanceof PodEntity pod && !Wrecks.isWreck(pod));
	}
}
