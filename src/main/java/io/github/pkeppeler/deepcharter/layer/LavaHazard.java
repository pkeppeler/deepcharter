package io.github.pkeppeler.deepcharter.layer;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Lava as a pod hazard (SPEC section 10): a pod in or touching lava loses hull each tick, and at 0 it is a wreck (#67).
 * A radiator cuts the loss by the tier's damage multiplier. The pod shields the pilot seated in it, as it does from gas (#288):
 * lava and the burning it starts do not hurt a rider, and its fire is put out, so the hull is what lava takes. Players outside a
 * pod, and the crew of a wreck, burn by vanilla's rules. The pod flags {@link PodEntity#hullBurning()} and hisses while it burns.
 */
public final class LavaHazard {
	private static final int TICKS_PER_SECOND = 20;
	/** How far past the pod's box a lava block still counts as touching it. */
	public static final double TOUCH_REACH = 0.01;

	private LavaHazard() {
	}

	public static void init() {
		PodEvents.AFTER_TICK.register(LavaHazard::tick);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(LavaHazard::allowDamage);
	}

	private static void tick(PodEntity pod) {
		boolean touching = touchesLava(pod);
		if (pod.hullBurning() != touching) {
			pod.setHullBurning(touching);
		}
		for (Entity rider : pod.getPassengers()) {
			if (rider.getRemainingFireTicks() > 0) {
				rider.clearFire();
			}
		}
		if (!touching) {
			return;
		}
		float radiator = PodComponents.radiatorRatio(pod);
		pod.damageHull(LayerTuning.DEFAULT.lavaHullPerSecond() / TICKS_PER_SECOND * radiator);
		if (pod.tickCount % LayerTuning.DEFAULT.lavaCueTicks() == 0) {
			pod.level().playSound(null, pod.getX(), pod.getY(), pod.getZ(), DeepSound.POD_HULL_BURNING.event(), SoundSource.NEUTRAL);
		}
	}

	/** A pilot seated in a working pod takes no lava damage and none from the fire lava lit; everything else is vanilla's. */
	private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		boolean lava = source.is(DamageTypes.LAVA) || source.is(DamageTypes.ON_FIRE);
		if (lava && entity.getVehicle() instanceof PodEntity pod && !Wrecks.isWreck(pod)) {
			// Lava lights its victim before it hurts, and the rider ticks after the pod: put the fire out here, or the pilot burns between pod ticks.
			entity.clearFire();
			return false;
		}
		return true;
	}

	/** Lava in an unloaded chunk is not seen: the probe reaches over a chunk edge, and a plain read there would load the chunk. */
	public static boolean touchesLava(PodEntity pod) {
		return new LoadedBlocks(pod.level()).getBlockStates(pod.getBoundingBox().inflate(TOUCH_REACH))
				.anyMatch(state -> state.getFluidState().is(FluidTags.LAVA));
	}
}
