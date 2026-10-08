package io.github.pkeppeler.deepcharter.ore;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;

/**
 * Gas pockets (SPEC section 10). A pocket that is mined, by a pod drill's bore or a player's hand, blasts the blocks
 * around it of the natural rock types ({@link HazardBlocks#NATURAL_ROCK}) and hurts the pods in reach. Removing a pocket
 * any other way (a command, carving, a fluid, a piston) only removes it. A block a player built of a rock type in the
 * tag (stone, deepslate, tuff) is cleared too: the tag knows types, not who placed a block.
 */
public final class GasHazard {
	/** True while a blast clears its blocks: a pocket in the blast goes with it and does not blast again. Server thread only. */
	private static boolean venting;

	private GasHazard() {
	}

	public static void init() {
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level instanceof ServerLevel server && state.is(HazardBlocks.GAS_POCKET)) {
				vent(server, pos);
			}
		});
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

	/** A gas pocket at {@code pos} has just been mined. */
	public static void vent(ServerLevel level, BlockPos pos) {
		if (venting) {
			return;
		}
		venting = true;
		try {
			int radius = OreTuning.DEFAULT.blastRadius();
			int depthFeet = Depth.feet(Depth.of(level, pos.getY()));
			for (PodEntity pod : level.getEntitiesOfClass(PodEntity.class, new AABB(pos).inflate(radius))) {
				float radiator = PodComponents.radiatorRatio(pod);
				pod.damageHull(damage(depthFeet, radiator));
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
}
