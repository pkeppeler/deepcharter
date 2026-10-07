package io.github.pkeppeler.deepcharter.repair;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.PodCargo;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.sound.DeepSound;

/**
 * The emergency teleporters (SPEC section 11): the pod and everyone in it go to the colony, and the cargo is left behind
 * as item entities where the pod was. Nothing mined teleports.
 */
final class Teleports {
	private Teleports() {
	}

	/**
	 * Where the teleporters take a pod: the colony. The colony (#64) is not built yet, so this is the world spawn. When
	 * {@code Colony.respawnPoint(server)} exists, it replaces the body of this method and nothing else.
	 */
	static GlobalPos destination(MinecraftServer server) {
		LevelData.RespawnData spawn = server.overworld().getRespawnData();
		return GlobalPos.of(spawn.dimension(), spawn.pos());
	}

	/** Sends {@code pod}, at most {@code scatter} blocks from the destination. Empty when it went, or the reason it did not. */
	static Optional<Component> send(PodEntity pod, double scatter) {
		ServerLevel from = (ServerLevel) pod.level();
		MinecraftServer server = from.getServer();
		GlobalPos target = destination(server);
		ServerLevel to = server.getLevel(target.dimension());
		if (to == null || !pod.canUsePortal(true) || !pod.canTeleport(from, to)) {
			return Consumables.refusal("teleport_blocked");
		}
		PodCargo cargo = pod.cargo();
		if (!cargo.isReadable()) {
			// The bay cannot be emptied, and nothing mined may travel.
			return Consumables.refusal("cargo_unreadable");
		}
		List<PodCargo.Entry> held = cargo.entries();
		Vec3 departure = pod.position();
		Vec3 landing = landing(to, target.pos(), scatter);
		Entity arrived = pod.teleport(new TeleportTransition(to, landing, Vec3.ZERO, pod.getYRot(), pod.getXRot(), TeleportTransition.DO_NOTHING));
		if (arrived == null) {
			return Consumables.refusal("teleport_blocked");
		}
		arrived.getPassengersAndSelf().forEach(Entity::resetFallDistance);
		// The saved cargo travelled with the pod (a crossing is a new entity): empty the arriving bay and leave the ore at the start.
		if (arrived instanceof PodEntity moved) {
			moved.cargo().dump(moved);
		}
		for (PodCargo.Entry entry : held) {
			from.addFreshEntity(new ItemEntity(from, departure.x, departure.y, departure.z, entry.stack().copy()));
		}
		from.playSound(null, departure.x, departure.y, departure.z, DeepSound.TERMINAL_TELEPORT.event(), SoundSource.PLAYERS);
		to.playSound(null, landing.x, landing.y, landing.z, DeepSound.TERMINAL_TELEPORT.event(), SoundSource.PLAYERS);
		return Optional.empty();
	}

	/** On the surface at the destination's column, moved by a random distance of at most {@code scatter} blocks. */
	private static Vec3 landing(ServerLevel level, BlockPos destination, double scatter) {
		double angle = level.getRandom().nextDouble() * 2 * Math.PI;
		double distance = level.getRandom().nextDouble() * scatter;
		double x = destination.getX() + 0.5 + Math.cos(angle) * distance;
		double z = destination.getZ() + 0.5 + Math.sin(angle) * distance;
		BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(x, destination.getY(), z));
		return new Vec3(x, ground.getY(), z);
	}
}
