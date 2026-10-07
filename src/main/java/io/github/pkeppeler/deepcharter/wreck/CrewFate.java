package io.github.pkeppeler.deepcharter.wreck;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;

/**
 * What happens to a pod's crew when the pod becomes a wreck: they die, as a Continuity Event, with vanilla's drops, and
 * a player wakes at the Continuity Office. The player's respawn point is not changed: the player is marked before the
 * kill and moved to the office after the respawn. A mark lost on a restart leaves the player at their bed.
 *
 * <p>A player whose client is still loading is immune to all damage, so a kill can fail. A member that survives is
 * killed again each server tick, for up to {@link #RETRY_TICKS} ticks, and a warning is logged if that fails too.
 */
final class CrewFate {
	static final int RETRY_TICKS = 200;

	private record Pending(Entity member, ServerLevel level, int[] ticksLeft) {
	}

	private static final List<Pending> PENDING = new ArrayList<>();
	/** Players that wake at the office after their next respawn. */
	private static final Set<UUID> WAKE_AT_OFFICE = new HashSet<>();
	/** Players that have respawned and are moved to the office at the end of the tick. */
	private static final Set<UUID> WOKEN = new HashSet<>();
	/** Players already logged for a missing office, so a repeat kill does not repeat the line. */
	private static final Set<UUID> REPORTED = new HashSet<>();

	private CrewFate() {
	}

	static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			retry();
			moveWokenToOffice(server);
		});
		// The connection still holds the dead player here, so a teleport waits for the end of the tick.
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (!alive && WAKE_AT_OFFICE.remove(newPlayer.getUUID())) {
				WOKEN.add(newPlayer.getUUID());
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register(
				(handler, server) -> WAKE_AT_OFFICE.remove(handler.getPlayer().getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			WAKE_AT_OFFICE.clear();
			WOKEN.clear();
			REPORTED.clear();
		});
	}

	static void die(Entity member, ServerLevel level) {
		if (member instanceof ServerPlayer player) {
			markToWakeAtOffice(player, level.getServer());
		}
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
				if (pending.member() instanceof ServerPlayer player) {
					WAKE_AT_OFFICE.remove(player.getUUID());
				}
				DeepCharter.LOGGER.warn("{} survived a wreck for {} ticks and was left alive",
						pending.member().getName().getString(), RETRY_TICKS);
			}
		}
	}

	private static void markToWakeAtOffice(ServerPlayer player, MinecraftServer server) {
		if (Colony.respawnPoint(server).isPresent()) {
			WAKE_AT_OFFICE.add(player.getUUID());
		} else if (REPORTED.add(player.getUUID())) {
			String name = player.getName().getString();
			if (ColonySite.get(server).isReadable()) {
				DeepCharter.LOGGER.warn("{} died in a wreck but the colony is not built: they respawn as usual", name);
			} else {
				DeepCharter.LOGGER.error("{} died in a wreck but the colony data is unreadable: they respawn as usual", name);
			}
		}
	}

	private static void moveWokenToOffice(MinecraftServer server) {
		if (WOKEN.isEmpty()) {
			return;
		}
		Optional<GlobalPos> office = Colony.respawnPoint(server);
		Set<UUID> ids = Set.copyOf(WOKEN);
		WOKEN.clear();
		for (UUID id : ids) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null && office.isPresent()) {
				Vec3 at = Vec3.atBottomCenterOf(office.get().pos());
				player.teleportTo(server.overworld(), at.x, at.y, at.z, Set.of(), 0f, 0f, true);
			}
		}
	}

	private static boolean isStillAlive(Entity member) {
		return !member.isRemoved() && (!(member instanceof LivingEntity living) || !living.isDeadOrDying());
	}
}
