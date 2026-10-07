package io.github.pkeppeler.deepcharter.wreck;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayer.RespawnConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.LevelData;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;

/**
 * What happens to a pod's crew when the pod becomes a wreck: they die, as a Continuity Event, with vanilla's drops.
 * A player respawns at the Continuity Office, even with a bed set: before the kill, their respawn point is set to the
 * office, and it is put back when they respawn. The bed is kept: the redirect is for this death only. A player that
 * leaves while alive, such as one that the kill has not reached yet, gets their respawn point back at once. If the colony has no respawn point,
 * or its dimension is not loaded, the player respawns as usual and that is logged. The remembered respawn points are not
 * saved: a server that stops while a crew member is at the death screen leaves them at the office.
 *
 * <p>A player whose client is still loading is immune to all damage, so a kill can fail. A member that survives is
 * killed again each server tick, for up to {@link #RETRY_TICKS} ticks, and a warning is logged if that fails too.
 */
final class CrewFate {
	static final int RETRY_TICKS = 200;

	private record Pending(Entity member, ServerLevel level, int[] ticksLeft) {
	}

	private static final List<Pending> PENDING = new ArrayList<>();
	/** The respawn point each redirected player had before, empty for none. */
	private static final Map<UUID, Optional<RespawnConfig>> ORIGINAL = new HashMap<>();

	private CrewFate() {
	}

	static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> retry());
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> restoreRespawnPoint(newPlayer));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (!handler.getPlayer().isDeadOrDying()) {
				restoreRespawnPoint(handler.getPlayer());
			}
		});
	}

	static void die(Entity member, ServerLevel level) {
		if (member instanceof ServerPlayer player) {
			redirectRespawnToColony(player, level);
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
					restoreRespawnPoint(player);
				}
				DeepCharter.LOGGER.warn("{} survived a wreck for {} ticks and was left alive", pending.member().getName().getString(), RETRY_TICKS);
			}
		}
	}

	private static void redirectRespawnToColony(ServerPlayer player, ServerLevel level) {
		if (ORIGINAL.containsKey(player.getUUID())) {
			return;
		}
		Optional<GlobalPos> office = Colony.respawnPoint(level.getServer());
		if (office.isEmpty() || level.getServer().getLevel(office.get().dimension()) == null) {
			DeepCharter.LOGGER.error("{} died in a wreck but the colony has no usable respawn point ({}): they respawn as usual",
					player.getName().getString(), office.map(GlobalPos::toString).orElse("not built or unreadable"));
			return;
		}
		ORIGINAL.put(player.getUUID(), Optional.ofNullable(player.getRespawnConfig()));
		GlobalPos at = office.get();
		player.setRespawnPosition(new RespawnConfig(LevelData.RespawnData.of(at.dimension(), at.pos(), 0f, 0f), true), false);
	}

	private static void restoreRespawnPoint(ServerPlayer player) {
		Optional<RespawnConfig> original = ORIGINAL.remove(player.getUUID());
		if (original != null) {
			player.setRespawnPosition(original.orElse(null), false);
		}
	}

	private static boolean isStillAlive(Entity member) {
		return !member.isRemoved() && (!(member instanceof LivingEntity living) || !living.isDeadOrDying());
	}
}
