package io.github.pkeppeler.deepcharter.repair;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.pod.PodCargo;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.sound.DeepSound;

/**
 * The emergency teleporters (SPEC section 11): the pod and everyone in it go to the colony, and the cargo is left behind
 * as item entities where the pod was. Nothing mined teleports.
 */
final class Teleports {
	/** How many random spots a scattering teleporter tries before it falls back to the exact column. */
	private static final int SCATTER_TRIES = 5;

	private Teleports() {
	}

	/**
	 * Where the teleporters take a pod: the colony's respawn point, or the world spawn while the colony is not built or its
	 * data is unreadable.
	 */
	static GlobalPos destination(MinecraftServer server) {
		return Colony.respawnPoint(server).orElseGet(() -> {
			LevelData.RespawnData spawn = server.overworld().getRespawnData();
			return GlobalPos.of(spawn.dimension(), spawn.pos());
		});
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
		// Everything that can refuse comes before the first change: a refused teleport moves nothing and keeps the item.
		Optional<Vec3> spot = safeLanding(to, pod, target.pos(), scatter);
		if (spot.isEmpty()) {
			return Consumables.refusal("teleport_blocked");
		}
		Vec3 landing = spot.get();
		List<PodCargo.Entry> held = cargo.entries();
		Vec3 departure = pod.position();
		Entity arrived = pod.teleport(new TeleportTransition(to, landing, Vec3.ZERO, pod.getYRot(), pod.getXRot(), TeleportTransition.DO_NOTHING));
		if (arrived == null) {
			return Consumables.refusal("teleport_blocked");
		}
		arrived.getPassengersAndSelf().forEach(Entity::resetFallDistance);
		// The ore is spilled first, so that a failure after it loses nothing: the bay is emptied last.
		for (PodCargo.Entry entry : held) {
			from.addFreshEntity(new ItemEntity(from, departure.x, departure.y, departure.z, entry.stack().copy()));
		}
		// The saved cargo travelled with the pod (a crossing is a new entity): empty the arriving bay.
		if (arrived instanceof PodEntity moved) {
			moved.cargo().dump(moved);
		}
		from.playSound(null, departure.x, departure.y, departure.z, DeepSound.TERMINAL_TELEPORT.event(), SoundSource.PLAYERS);
		to.playSound(null, landing.x, landing.y, landing.z, DeepSound.TERMINAL_TELEPORT.event(), SoundSource.PLAYERS);
		return Optional.empty();
	}

	/**
	 * A spot on the ground where the pod fits: at the destination's column, or for a scatter above 0 first
	 * {@link #SCATTER_TRIES} random spots within {@code scatter} blocks of it. The look-up loads the chunk if it is not loaded.
	 * Empty when no spot is free of blocks and of fluid in and under the pod.
	 */
	private static Optional<Vec3> safeLanding(ServerLevel level, PodEntity pod, BlockPos destination, double scatter) {
		if (scatter > 0) {
			for (int attempt = 0; attempt < SCATTER_TRIES; attempt++) {
				double angle = level.getRandom().nextDouble() * 2 * Math.PI;
				double distance = level.getRandom().nextDouble() * scatter;
				Optional<Vec3> spot = fit(level, pod, destination.getX() + 0.5 + Math.cos(angle) * distance,
						destination.getZ() + 0.5 + Math.sin(angle) * distance, destination.getY());
				if (spot.isPresent()) {
					return spot;
				}
			}
		}
		return fit(level, pod, destination.getX() + 0.5, destination.getZ() + 0.5, destination.getY());
	}

	private static Optional<Vec3> fit(ServerLevel level, PodEntity pod, double x, double z, int nearY) {
		// An unloaded chunk answers every height with the bottom of the world, so load what the pod will stand on first.
		AABB reach = pod.getDimensions(pod.getPose()).makeBoundingBox(new Vec3(x, nearY, z)).inflate(1.0);
		for (int chunkX = SectionPos.blockToSectionCoord(reach.minX); chunkX <= SectionPos.blockToSectionCoord(reach.maxX); chunkX++) {
			for (int chunkZ = SectionPos.blockToSectionCoord(reach.minZ); chunkZ <= SectionPos.blockToSectionCoord(reach.maxZ); chunkZ++) {
				level.getChunk(chunkX, chunkZ);
			}
		}
		BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(x, nearY, z));
		Vec3 spot = new Vec3(x, ground.getY(), z);
		AABB box = pod.getDimensions(pod.getPose()).makeBoundingBox(spot);
		if (!level.noBlockCollision(pod, box)) {
			return Optional.empty();
		}
		// The block under the pod counts too: a pod set down on lava is lost.
		AABB wet = box.expandTowards(0, -1, 0);
		for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(wet.minX, wet.minY, wet.minZ), BlockPos.containing(wet.maxX, wet.maxY, wet.maxZ))) {
			if (!level.getFluidState(cell).isEmpty()) {
				return Optional.empty();
			}
		}
		return Optional.of(spot);
	}
}
