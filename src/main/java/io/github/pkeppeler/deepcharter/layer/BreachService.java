package io.github.pkeppeler.deepcharter.layer;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Breach crossing: an entity that drops below its layer's floor appears at the same X/Z under the
 * ceiling of the next layer, and one that rises above a layer's top appears above the crust of the
 * layer above. A vehicle crosses as one with its passengers.
 *
 * <p>The arrival point is {@link LayerTuning#pocketHeight()} blocks inside the destination, so an
 * entity has to move that far before it could cross back: that distance is the hysteresis. The last
 * layer's floor has no crossing, and neither has the top of layer 1.
 *
 * <p>Players cannot break {@code breach_crust} by hand. Drills do it through {@link #breakCrust}.
 */
public final class BreachService {
	private BreachService() {
	}

	public static void init() {
		ServerTickEvents.END_LEVEL_TICK.register(BreachService::crossEntities);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !state.is(LayerBlocks.BREACH_CRUST));
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
		OptionalInt found = LayerChain.layerOf(level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier());
		if (found.isEmpty()) {
			return;
		}
		int layer = found.getAsInt();
		int layers = LayerChain.count(level.registryAccess());
		// Collect first: a crossing changes the entity lists being iterated.
		List<Entity> descending = new ArrayList<>();
		List<Entity> ascending = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity.isPassenger() || entity.isRemoved()) {
				continue;
			}
			if (entity.getY() < level.getMinY() && layer < layers) {
				descending.add(entity);
			} else if (entity.getY() >= level.getMaxY() + 1 && layer > 1) {
				ascending.add(entity);
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
		LayerTuning tuning = LayerTuning.DEFAULT;
		boolean descending = toLayer > fromLayer;
		int arrivalY = descending
				? to.getMaxY() - tuning.pocketHeight()
				: to.getMinY() + tuning.crustThickness();
		carvePocket(to, BlockPos.containing(entity.getX(), arrivalY, entity.getZ()), tuning);

		Vec3 position = new Vec3(entity.getX(), arrivalY, entity.getZ());
		Entity arrived = entity.teleport(new TeleportTransition(to, position, Vec3.ZERO, entity.getYRot(), entity.getXRot(), TeleportTransition.DO_NOTHING));
		if (arrived == null) {
			return;
		}
		arrived.getPassengersAndSelf().forEach(crossed -> {
			crossed.resetFallDistance();
			BreachEvents.CROSSED.invoker().onCrossed(crossed, from, to, fromLayer, toLayer);
		});
	}

	/** Air for {@code pocketHeight} blocks up from {@code bottom}, {@code pocketRadius} blocks out each way. */
	private static void carvePocket(ServerLevel level, BlockPos bottom, LayerTuning tuning) {
		int radius = tuning.pocketRadius();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
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
