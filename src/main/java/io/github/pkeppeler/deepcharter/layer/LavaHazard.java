package io.github.pkeppeler.deepcharter.layer;

import net.minecraft.tags.FluidTags;

import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;

/**
 * Lava as a pod hazard (SPEC section 10): a pod in or touching lava loses hull each tick, and at 0 it is a wreck (#67).
 * A radiator cuts the loss by the tier's damage multiplier. A rider burns by vanilla's own rule, as its body is in the
 * lava too; nothing here treats riders.
 */
public final class LavaHazard {
	private static final int TICKS_PER_SECOND = 20;
	/** How far past the pod's box a lava block still counts as touching it. */
	private static final double TOUCH_REACH = 0.01;

	private LavaHazard() {
	}

	public static void init() {
		PodEvents.AFTER_TICK.register(LavaHazard::tick);
	}

	private static void tick(PodEntity pod) {
		if (pod.hull() <= 0f || !touchesLava(pod)) {
			return;
		}
		float radiator = UpgradeTuning.DEFAULT.ratio(ComponentTrack.RADIATOR, PodComponents.effectiveTier(pod, ComponentTrack.RADIATOR));
		pod.damageHull(LayerTuning.DEFAULT.lavaHullPerSecond() / TICKS_PER_SECOND * radiator);
	}

	private static boolean touchesLava(PodEntity pod) {
		return pod.level().getBlockStates(pod.getBoundingBox().inflate(TOUCH_REACH))
				.anyMatch(state -> state.getFluidState().is(FluidTags.LAVA));
	}
}
