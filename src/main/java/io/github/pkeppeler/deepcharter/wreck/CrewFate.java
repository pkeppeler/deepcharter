package io.github.pkeppeler.deepcharter.wreck;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * What happens to a pod's crew when the pod becomes a wreck: they die, as a Continuity Event, with vanilla's drops.
 * Dying players respawn at their normal respawn point. The colony (#64) does not exist yet; when it does, this is the
 * one place to send them there instead, for example by setting the player's respawn point before the kill.
 */
final class CrewFate {
	private CrewFate() {
	}

	static void die(Entity member, ServerLevel level) {
		member.kill(level);
	}
}
