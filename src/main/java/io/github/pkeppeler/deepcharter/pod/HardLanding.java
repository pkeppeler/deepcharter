package io.github.pkeppeler.deepcharter.pod;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;

import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Hard landings (#319): a pod's landing costs hull by the speed it sinks at when it lands, not by the distance it fell, so a pod
 * that brakes with its rotor survives a deep open shaft and a free fall does not. Past {@link PodStats#hardLandingSpeed} the hull
 * loses {@link PodStats#hullDamagePerSpeed} for each block per tick of speed. The pod shields the pilot and crew seated in it from
 * fall damage, as it does from lava (#288): the hull takes the landing. Players outside a pod, and the crew of a wreck, take vanilla's.
 */
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
