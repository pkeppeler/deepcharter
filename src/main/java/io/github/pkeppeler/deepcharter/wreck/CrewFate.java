package io.github.pkeppeler.deepcharter.wreck;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * What happens to a pod's crew when the pod becomes a wreck: they die, as a Continuity Event, with vanilla's drops.
 * Dying players respawn at their normal respawn point. The colony (#64) does not exist yet; when it does, this is the
 * one place to send them there instead, for example by setting the player's respawn point before the kill.
 *
 * <p>A player whose client is still loading is immune to all damage, so a kill can fail. A member that survives is
 * killed again each server tick, for up to {@link #RETRY_TICKS} ticks, and a warning is logged if that fails too.
 */
final class CrewFate {
	static final int RETRY_TICKS = 200;

	private record Pending(Entity member, ServerLevel level, int[] ticksLeft) {
	}

	private static final List<Pending> PENDING = new ArrayList<>();

	private CrewFate() {
	}

	static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> retry());
	}

	static void die(Entity member, ServerLevel level) {
		member.kill(level);
		if (isStillAlive(member)) {
			PENDING.add(new Pending(member, level, new int[] {RETRY_TICKS}));
		}
	}

	private static void retry() {
		for (Pending pending : List.copyOf(PENDING)) {
			if (!isStillAlive(pending.member())) {
				PENDING.remove(pending);
				continue;
			}
			pending.member().kill(pending.level());
			if (!isStillAlive(pending.member())) {
				PENDING.remove(pending);
			} else if (--pending.ticksLeft()[0] <= 0) {
				PENDING.remove(pending);
				DeepCharter.LOGGER.warn("{} survived a wreck for {} ticks and was left alive", pending.member().getName().getString(), RETRY_TICKS);
			}
		}
	}

	private static boolean isStillAlive(Entity member) {
		return !member.isRemoved() && (!(member instanceof LivingEntity living) || !living.isDeadOrDying());
	}
}
