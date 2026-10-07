package io.github.pkeppeler.deepcharter.ore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.pod.PodEntity;

/**
 * Gas pockets (SPEC section 10). A pocket that is removed blasts the blocks around it that are
 * {@link HazardBlocks#NATURAL_ROCK} and hurts the pods in reach. Blocks players built are never in the tag, so they survive.
 */
public final class GasHazard {
	/** True while a blast clears its blocks: a pocket in the blast goes with it and does not blast again. Server thread only. */
	private static boolean venting;

	private GasHazard() {
	}

	/**
	 * Hull points a blast costs: the depth in feet times the radiator factor times {@code OreTuning.gasDamagePerFoot}.
	 * The depth is clamped to 0, so gas above sea level does nothing.
	 *
	 * @throws IllegalArgumentException if {@code radiator} is negative or not a finite number
	 */
	public static float damage(int depthFeet, float radiator) {
		if (!(radiator >= 0f) || Float.isInfinite(radiator)) {
			throw new IllegalArgumentException("A radiator factor is a finite number of at least 0, got " + radiator);
		}
		return Math.max(0, depthFeet) * radiator * OreTuning.DEFAULT.gasDamagePerFoot();
	}

	/** A gas pocket at {@code pos} has just been removed. */
	static void vent(ServerLevel level, BlockPos pos) {
		if (venting) {
			return;
		}
		venting = true;
		try {
			int radius = OreTuning.DEFAULT.blastRadius();
			float damage = damage(Depth.feet(Depth.of(level, pos.getY())), OreTuning.DEFAULT.stockRadiator());
			for (PodEntity pod : level.getEntitiesOfClass(PodEntity.class, new AABB(pos).inflate(radius))) {
				hurt(pod, damage);
			}
			for (BlockPos cell : BlockPos.betweenClosed(pos.offset(-radius, -radius, -radius), pos.offset(radius, radius, radius))) {
				if (level.getBlockState(cell).is(HazardBlocks.NATURAL_ROCK)) {
					level.destroyBlock(cell.immutable(), false);
				}
			}
		} finally {
			venting = false;
		}
	}

	/**
	 * The one place a blast touches a pod. #60's {@code PodStats} will give the pod's radiator for
	 * {@code OreTuning.stockRadiator} and its hull maximum, and {@code PodEntity.damageHull} will replace the line.
	 */
	private static void hurt(PodEntity pod, float damage) {
		pod.setHull(Math.max(0f, pod.hull() - damage));
	}
}
