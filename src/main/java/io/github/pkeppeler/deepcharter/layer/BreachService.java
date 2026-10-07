package io.github.pkeppeler.deepcharter.layer;

import java.util.LinkedHashSet;
import java.util.OptionalInt;
import java.util.Set;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;

/**
 * Breach crossing: a player who drops below their layer's floor appears at the same X/Z under the
 * ceiling of the next layer, and one who rises above a layer's top appears above the crust of the
 * layer above. A vehicle crosses as one with its passengers. The overworld is layer 0: its floor
 * leads into layer 1, and the top of layer 1 leads back up to it.
 *
 * <p>Only players, and the vehicles that carry them, cross. The arrival point is
 * {@link LayerTuning#pocketHeight()} blocks inside the destination, so an entity has to move that
 * far before it could cross back: that distance is the hysteresis. The last layer's floor has no
 * crossing, and neither has the top of the overworld.
 *
 * <p>Survival and adventure players cannot break {@code breach_crust} by hand; creative players can.
 * Drills do it through {@link #breakCrust}.
 */
public final class BreachService {
	/** How far a crossing looks for a pocket column free of block entities. */
	private static final int SEARCH_RADIUS = 16;

	private BreachService() {
	}

	public static void init() {
		ServerTickEvents.END_LEVEL_TICK.register(BreachService::crossEntities);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
				player.isCreative() || !state.is(LayerBlocks.BREACH_CRUST));
	}

	/**
	 * The drill's way through the crust. Hand-breaking is refused by the player block-break event;
	 * this removes the block directly, so it is not subject to that guard. Returns false when
	 * {@code pos} holds no crust.
	 */
	public static boolean breakCrust(ServerLevel level, BlockPos pos) {
		if (!level.getBlockState(pos).is(LayerBlocks.BREACH_CRUST)) {
			return false;
		}
		return level.destroyBlock(pos, false);
	}

	private static void crossEntities(ServerLevel level) {
		OptionalInt found = LayerChain.indexOf(level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier());
		if (found.isEmpty()) {
			return;
		}
		int layer = found.getAsInt();
		int layers = LayerChain.count(level.registryAccess());
		// Anything else that falls out is the void's. Collect first: a crossing changes the player list.
		Set<Entity> descending = new LinkedHashSet<>();
		Set<Entity> ascending = new LinkedHashSet<>();
		for (ServerPlayer player : level.players()) {
			Entity root = player.getRootVehicle();
			if (root.isRemoved()) {
				continue;
			}
			if (root.getY() < level.getMinY() && layer < layers) {
				descending.add(root);
			} else if (root.getY() > level.getMaxY() && layer > LayerChain.SURFACE) {
				ascending.add(root);
			}
		}
		for (Entity entity : descending) {
			cross(entity, level, layer, layer + 1);
		}
		for (Entity entity : ascending) {
			cross(entity, level, layer, layer - 1);
		}
	}

	private static void cross(Entity entity, ServerLevel from, int fromLayer, int toLayer) {
		ServerLevel to = from.getServer().getLevel(LayerChain.dimension(toLayer));
		if (to == null) {
			throw new IllegalStateException("Layer " + toLayer + " is in the registry but its level is not loaded");
		}
		// An entity that cannot change dimension stays put, and nothing is carved for it.
		if (!entity.canUsePortal(true) || !entity.canTeleport(from, to)) {
			return;
		}
		LayerTuning tuning = LayerTuning.DEFAULT;
		boolean descending = toLayer > fromLayer;
		int arrivalY = descending
				? to.getMaxY() - tuning.pocketHeight()
				: to.getMinY() + tuning.crustThickness();
		BlockPos requested = BlockPos.containing(entity.getX(), arrivalY, entity.getZ());
		BlockPos pocket = preparePocket(to, requested, tuning);

		Vec3 position = pocket.equals(requested)
				? new Vec3(entity.getX(), arrivalY, entity.getZ())
				: new Vec3(pocket.getX() + 0.5, arrivalY, pocket.getZ() + 0.5);
		Entity arrived = entity.teleport(new TeleportTransition(to, position, Vec3.ZERO, entity.getYRot(), entity.getXRot(), TeleportTransition.DO_NOTHING));
		if (arrived == null) {
			throw new IllegalStateException("Vanilla refused to teleport " + entity + " from layer " + fromLayer + " to layer " + toLayer);
		}
		arrived.getPassengersAndSelf().forEach(crossed -> {
			crossed.resetFallDistance();
			BreachEvents.CROSSED.invoker().onCrossed(crossed, from, to, fromLayer, toLayer);
		});
	}

	/**
	 * Makes room for an arrival: air for {@code pocketHeight} blocks up from {@code bottom},
	 * {@code pocketRadius} blocks out each way, over a solid floor. Block entities and the Conduit's casing are never
	 * deleted, so a column whose pocket holds one is skipped for the nearest clear one. Returns the column used.
	 */
	private static BlockPos preparePocket(ServerLevel level, BlockPos bottom, LayerTuning tuning) {
		for (int ring = 0; ring <= SEARCH_RADIUS; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
						continue;
					}
					BlockPos column = bottom.offset(dx, 0, dz);
					if (!holdsBlockEntity(level, column, tuning)) {
						carve(level, column, tuning);
						return column;
					}
				}
			}
		}
		throw new IllegalStateException("No pocket free of block entities within " + SEARCH_RADIUS + " blocks of " + bottom);
	}

	private static boolean holdsBlockEntity(ServerLevel level, BlockPos bottom, LayerTuning tuning) {
		int radius = tuning.pocketRadius();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = -1; dy < tuning.pocketHeight(); dy++) {
					BlockPos pos = bottom.offset(dx, dy, dz);
					if (level.getBlockEntity(pos) != null || level.getBlockState(pos).is(ColonyBlocks.CONDUIT)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static void carve(ServerLevel level, BlockPos bottom, LayerTuning tuning) {
		int radius = tuning.pocketRadius();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				BlockPos floor = bottom.offset(dx, -1, dz);
				BlockState below = level.getBlockState(floor);
				if (below.isAir() || !below.getFluidState().isEmpty()) {
					level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
				}
				for (int dy = 0; dy < tuning.pocketHeight(); dy++) {
					BlockPos pos = bottom.offset(dx, dy, dz);
					if (!level.getBlockState(pos).isAir()) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
		}
	}
}
