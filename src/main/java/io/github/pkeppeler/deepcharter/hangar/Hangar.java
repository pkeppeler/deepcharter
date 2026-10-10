package io.github.pkeppeler.deepcharter.hangar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyEvents;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.Serials;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * The colony hangar. When the colony is built it gets a console (the {@link HangarTerminal}) and the derelict founding Mole,
 * a wreck that nobody owns. The first charter to put in the last of the four {@link HangarParts} repairs the Mole and owns it
 * as {@code MOLE-0001}, because every other way to get a registered Mole needs the console to be repaired first.
 *
 * <p>Every method here is safe on a callback path: unreadable hangar data is logged once and skipped.
 */
public final class Hangar {
	/** The console stands this far from the hangar anchor, at its east side facing the bay. */
	private static final BlockPos CONSOLE_OFFSET = new BlockPos(5, 0, -3);

	private Hangar() {
	}

	static void register() {
		ColonyEvents.BUILT.register(Hangar::onBuilt);
		TerminalEvents.REPAIRED.register(Hangar::onRepaired);
		ServerEntityEvents.ENTITY_LOAD.register(Hangar::onLoad);
	}

	/** Where the console stands: beside the hangar bay. Empty before the colony is built. */
	public static Optional<BlockPos> consolePos(MinecraftServer server) {
		return Colony.anchor(server, ColonyAnchor.HANGAR).map(anchor -> anchor.offset(CONSOLE_OFFSET));
	}

	/**
	 * Places the console on every build, since a rebuild clears the pad (its repair lives in {@code RepairState}), and the
	 * derelict Mole once. A colony built before the hangar existed is not given one.
	 */
	public static void onBuilt(MinecraftServer server, ColonySite.Placed colony) {
		ServerLevel level = server.overworld();
		BlockPos anchor = colony.anchors().get(ColonyAnchor.HANGAR);
		placeConsole(level, anchor);
		Optional<HangarData> data = HangarData.readable(server);
		if (data.isEmpty() || data.get().state().derelict().isPresent()) {
			return;
		}
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.STRUCTURE);
		if (pod == null) {
			DeepCharter.LOGGER.error("Could not make the derelict Mole for the hangar: the pod was not created");
			return;
		}
		pod.setPos(Vec3.atBottomCenterOf(anchor));
		pod.setHull(0f);
		if (!level.addFreshEntity(pod)) {
			DeepCharter.LOGGER.error("Could not place the derelict Mole at {}: the world refused the entity", anchor.toShortString());
			return;
		}
		data.get().placeDerelict(pod.getUUID());
		DeepCharter.LOGGER.info("Placed the derelict Mole {} in the hangar at {}", pod.getUUID(), anchor.toShortString());
	}

	private static void placeConsole(ServerLevel level, BlockPos anchor) {
		BlockPos console = anchor.offset(CONSOLE_OFFSET);
		if (!level.getBlockState(console).is(HangarTerminal.TYPE.block())) {
			BlockState state = HangarTerminal.TYPE.block().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
			level.setBlock(console, state, 3);
		}
	}

	/** The founding Mole, if it is placed and loaded. */
	public static Optional<PodEntity> derelict(MinecraftServer server) {
		return HangarData.readable(server).flatMap(data -> data.state().derelict()).flatMap(id -> podOf(server, id));
	}

	private static Optional<PodEntity> podOf(MinecraftServer server, UUID id) {
		return Optional.ofNullable(server.overworld().getEntity(id)).filter(PodEntity.class::isInstance).map(PodEntity.class::cast);
	}

	/** True for the founding Mole until it is repaired: it is repaired with its four parts and is not for sale as a wreck. */
	static boolean isUnrepairedDerelict(HangarData data, PodEntity pod) {
		HangarData.State state = data.state();
		return !state.founded() && state.derelict().equals(Optional.of(pod.getUUID()));
	}

	/**
	 * How many pods the charter has: the ones the hangar gave it, and every loaded pod it owns. A pod in an unloaded chunk that
	 * the hangar did not give it is not counted, because the world keeps no list of pods.
	 */
	static int podsOf(MinecraftServer server, HangarData data, CharterId charter) {
		Set<UUID> pods = new HashSet<>(data.held(charter));
		for (ServerLevel level : server.getAllLevels()) {
			for (PodEntity pod : level.getEntities(EntityTypeTest.forClass(PodEntity.class), pod -> true)) {
				PodComponents.registration(pod).filter(registration -> registration.owner().equals(charter)).ifPresent(registration -> pods.add(pod.getUUID()));
			}
		}
		return pods.size();
	}

	/**
	 * A free place in the bay for a new pod of {@code chassis}, the nearest to the anchor first; empty when the bay is full. The
	 * places are a bore width and {@link HangarTuning#slotGap()} apart, as many as fit within {@link HangarTuning#bayRadius()} of the anchor, and
	 * a place is free when a pod's box fits there clear of blocks and of other pods.
	 */
	public static Optional<Vec3> freeSlot(ServerLevel level, BlockPos anchor, Chassis chassis) {
		HangarTuning tuning = HangarTuning.DEFAULT;
		int spacing = chassis.boreWidth() + tuning.slotGap();
		int reach = tuning.bayRadius() / spacing * spacing;
		List<Vec3> slots = new ArrayList<>();
		for (int dx = -reach; dx <= reach; dx += spacing) {
			for (int dz = -reach; dz <= reach; dz += spacing) {
				slots.add(Vec3.atBottomCenterOf(anchor.offset(dx, 0, dz)));
			}
		}
		slots.sort(Comparator.comparingDouble(slot -> slot.distanceToSqr(Vec3.atBottomCenterOf(anchor))));
		EntityType<PodEntity> type = PodRegistry.typeOf(chassis);
		return slots.stream().filter(slot -> {
			AABB box = type.getDimensions().makeBoundingBox(slot);
			return level.hasChunkAt(BlockPos.containing(slot)) && level.noCollision(box)
					&& level.getEntitiesOfClass(PodEntity.class, box).isEmpty();
		}).findFirst();
	}

	private static void onRepaired(MinecraftServer server, TerminalType type, Charter charter, ServerPlayer player) {
		if (type != HangarTerminal.TYPE) {
			return;
		}
		Optional<HangarData> data = HangarData.readable(server);
		if (data.isEmpty()) {
			return;
		}
		if (data.get().state().founder().isEmpty()) {
			data.get().setFounder(charter.id());
		}
		completeFounding(server, data.get());
	}

	private static void onLoad(Entity entity, ServerLevel level) {
		if (!(entity instanceof PodEntity pod)) {
			return;
		}
		Optional<HangarData> data = HangarData.readable(level.getServer());
		if (data.isPresent() && data.get().state().derelict().equals(Optional.of(pod.getUUID()))) {
			completeFounding(level.getServer(), data.get());
		}
	}

	/**
	 * Gives the founding Mole to the charter that repaired it: registers it (which gives it its serial) and restores it. A Mole that
	 * is not loaded now is finished when it loads. Never throws.
	 */
	private static void completeFounding(MinecraftServer server, HangarData data) {
		HangarData.State state = data.state();
		if (state.founded() || state.founder().isEmpty() || state.derelict().isEmpty()) {
			return;
		}
		Optional<PodEntity> pod = podOf(server, state.derelict().get());
		if (pod.isEmpty()) {
			return;
		}
		CharterId founder = state.founder().get();
		try {
			if (PodComponents.registration(pod.get()).isEmpty()) {
				if (!Serials.get(server).isReadable()) {
					// Logged once by the check; the hangar tries again when the Mole loads.
					return;
				}
				PodComponents.register(pod.get(), founder);
			}
			if (Wrecks.isWreck(pod.get())) {
				Wrecks.restore(pod.get(), pod.get().maxHull());
			}
			data.hold(founder, pod.get().getUUID());
			data.markFounded();
			String serial = PodComponents.registration(pod.get()).orElseThrow().serial();
			if (!serial.equals("MOLE-0001")) {
				DeepCharter.LOGGER.warn("The founding Mole got the serial {}, not MOLE-0001: another Mole was registered before the hangar console was repaired", serial);
			}
		} catch (RuntimeException e) {
			DeepCharter.LOGGER.error("Could not give the founding Mole to charter {}: it stays as it is, and the hangar tries again when it loads", founder, e);
		}
	}
}
