package io.github.pkeppeler.deepcharter.creature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;

/**
 * Puts a {@link LamplessFigure} on the rail site of Prospector's Run that a player is near, when the site has none: at most
 * {@link CreatureTuning#maxPerSite} on a site and {@link CreatureTuning#maxPerLevel} in the level, and none until
 * {@link CreatureTuning#respawnDelayTicks} after the last one faded. Nothing is saved: a figure is made from the rails as they stand.
 */
public final class LamplessFigures {
	private static final AtomicBoolean LOGGED_FAILURE = new AtomicBoolean();

	/** Game time at which the last figure of each level faded. Server thread only. */
	private static final Map<ResourceKey<Level>, Long> LAST_FADE = new HashMap<>();

	private LamplessFigures() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> LAST_FADE.clear());
		ServerTickEvents.END_LEVEL_TICK.register(LamplessFigures::tick);
	}

	static void faded(ServerLevel level) {
		LAST_FADE.put(level.dimension(), level.getGameTime());
	}

	/** A tick path: it logs a failure once and carries on. */
	private static void tick(ServerLevel level) {
		if (!level.dimension().equals(LayerChain.dimension(StructureKind.RAILS.layer()))
				|| level.getGameTime() % CreatureTuning.DEFAULT.spawnIntervalTicks() != 0) {
			return;
		}
		try {
			Optional<BlockPos> conduit = Colony.anchor(level.getServer(), ColonyAnchor.CONDUIT);
			if (conduit.isEmpty()) {
				return;
			}
			Set<StructureSite> visited = new HashSet<>();
			double range = CreatureTuning.DEFAULT.spawnRangeBlocks();
			for (ServerPlayer player : level.players()) {
				StructureSite site = LayerStructures.nearest(level.getSeed(), StructureKind.RAILS, level.getMinY(), level.getHeight(), player.blockPosition(), conduit.get());
				if (player.distanceToSqr(site.origin().getX() + 0.5, player.getY(), site.origin().getZ() + 0.5) <= range * range && visited.add(site)) {
					spawnAt(level, site);
				}
			}
		} catch (RuntimeException e) {
			if (LOGGED_FAILURE.compareAndSet(false, true)) {
				DeepCharter.LOGGER.error("Spawning the lampless figure failed in {}; this and later spawns may not happen", level.dimension().identifier(), e);
			}
		}
	}

	/**
	 * Puts one figure on a rail of {@code site}, walking either way along it, when the site and the level have room for it and the
	 * last figure faded long enough ago. It goes where the rail is loaded and ticking, unlit, and clear of every player; empty when
	 * there is no such place.
	 */
	public static Optional<LamplessFigure> spawnAt(ServerLevel level, StructureSite site) {
		CreatureTuning tuning = CreatureTuning.DEFAULT;
		Long lastFade = LAST_FADE.get(level.dimension());
		if (lastFade != null && level.getGameTime() - lastFade < tuning.respawnDelayTicks()) {
			return Optional.empty();
		}
		BoundingBox bounds = site.bounds();
		if (level.getEntities(CreatureRegistry.LAMPLESS_FIGURE, figure -> bounds.isInside(figure.blockPosition())).size() >= tuning.maxPerSite()
				|| level.getEntities(CreatureRegistry.LAMPLESS_FIGURE, figure -> true).size() >= tuning.maxPerLevel()) {
			return Optional.empty();
		}
		List<BlockPos> places = places(level, site, tuning);
		if (places.isEmpty()) {
			return Optional.empty();
		}
		BlockPos place = places.get(level.getRandom().nextInt(places.size()));
		LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.EVENT);
		if (figure == null) {
			throw new IllegalStateException("The lampless figure could not be created in " + level.dimension().identifier());
		}
		figure.setPos(place.getX() + 0.5, place.getY(), place.getZ() + 0.5);
		Direction along = site.alongZ() ? Direction.SOUTH : Direction.EAST;
		figure.setHeading(level.getRandom().nextBoolean() ? along : along.getOpposite());
		level.addFreshEntity(figure);
		return Optional.of(figure);
	}

	/** The rails of the site where a figure may appear. Checks the position is ticking before it reads a block, so that no chunk loads. */
	private static List<BlockPos> places(ServerLevel level, StructureSite site, CreatureTuning tuning) {
		BoundingBox bounds = site.bounds();
		int from = site.alongZ() ? bounds.minZ() : bounds.minX();
		int to = site.alongZ() ? bounds.maxZ() : bounds.maxX();
		double clearance = tuning.spawnClearance();
		List<BlockPos> places = new ArrayList<>();
		for (int along = from; along <= to; along++) {
			BlockPos pos = site.alongZ() ? new BlockPos(site.origin().getX(), site.origin().getY(), along) : new BlockPos(along, site.origin().getY(), site.origin().getZ());
			if (level.isPositionEntityTicking(pos)
					&& level.getBlockState(pos).is(BlockTags.RAILS)
					&& level.getBlockState(pos.above()).isAir()
					&& level.getBrightness(LightLayer.BLOCK, pos) < tuning.fadeBlockLight()
					&& level.getNearestPlayer(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, clearance, EntitySelector.NO_SPECTATORS) == null) {
				places.add(pos);
			}
		}
		return places;
	}
}
