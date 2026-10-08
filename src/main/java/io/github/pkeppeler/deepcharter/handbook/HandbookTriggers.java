package io.github.pkeppeler.deepcharter.handbook;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.google.common.base.Suppliers;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.hangar.HangarEvents;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeEvents;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTerminal;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Completes the directives of chapters 1 to 9 from what happens in the game, through {@link Directives#fire}. The directives of
 * chapter 1 are vanilla criteria and need nothing here (see their advancements). The rest listen to the events of the features
 * that own the deed, so no feature calls the handbook.
 *
 * <ul>
 *   <li>A terminal repaired, a purchase at the pump, a sale at the processor, a purchase at the upgrade terminal and a Mole bought at
 *       the hangar complete the directive of the player who did it, for the player's charter.</li>
 *   <li>A terminal is repaired for every charter at once, so a charter that began after the repair has nothing left to repair:
 *       {@link #creditRepairs} completes the repair directives of every online player whose charter has not done them.</li>
 *   <li>A Mole with a pilot is polled every {@link HandbookTuning#triggerPollTicks()} ticks: boarding, flying, drilling down
 *       {@link HandbookTuning#drillDownBlocks()} blocks below the colony's ground, and returning to the colony after that.</li>
 *   <li>A scanner bought ({@link UpgradeEvents#BOUGHT}), a hull repaired at the repair station, the first breach crossed
 *       ({@link BreachEvents#CROSSED}) and a Prospector restored ({@link HangarEvents#RESTORED}) complete the directive of the
 *       player who did it.</li>
 *   <li>A pod with a pilot who has a working scanner is polled on the same ticks, until its charter has found ore: the scanner's own
 *       slice, read from the chunks that are loaded, holds an ore. A towed Prospector is polled the same way: once it is in the
 *       colony, its tower's players tow it home.</li>
 *   <li>Every online player is polled on those ticks too ({@link #pollPlayer}) for what a place makes true: the Deep Claim, a
 *       Prospector wreck within {@link HandbookTuning#findProspectorBlocks()} blocks, and the floor of the Old Workings from a
 *       Prospector's seat.</li>
 * </ul>
 *
 * <p>Every callback here is on a gameplay path, so none of them throws: {@link Directives#fire} and
 * {@link HandbookProgress#completedFor} log unreadable saved data once and do nothing.
 */
public final class HandbookTriggers {
	private static final Identifier REPAIR_FUEL_PUMP = directive("back_online/repair_fuel_pump");
	private static final Identifier REPAIR_ORE_PROCESSOR = directive("back_online/repair_ore_processor");
	private static final Identifier REPAIR_UPGRADE_TERMINAL = directive("back_online/repair_upgrade_terminal");
	private static final Identifier REPAIR_MOLE = directive("meet_the_mole/repair_mole");
	private static final Identifier BOARD_MOLE = directive("meet_the_mole/board_mole");
	private static final Identifier REFUEL_MOLE = directive("fuel_is_life/refuel_mole");
	private static final Identifier FLY_MOLE = directive("fuel_is_life/fly_mole");
	private static final Identifier DRILL_DOWN = directive("fuel_is_life/drill_down");
	private static final Identifier RETURN_TO_COLONY = directive("fuel_is_life/return_to_colony");
	private static final Identifier SELL_ORE = directive("every_sale_counts/sell_ore");
	private static final Identifier BUY_COMPONENT = directive("every_sale_counts/buy_component");
	private static final Identifier INSTALL_COMPONENT = directive("every_sale_counts/install_component");
	private static final Identifier INSTALL_SCANNER = directive("seeing_below/install_scanner");
	private static final Identifier FIND_ORE = directive("seeing_below/find_ore");
	private static final Identifier REPAIR_HULL = directive("staying_safe/repair_hull");
	private static final Identifier REACH_DEEP_CLAIM = directive("staying_safe/reach_deep_claim");
	private static final Identifier BREACH_WORKINGS = directive("first_breach/breach_workings");
	private static final Identifier FIND_PROSPECTOR = directive("company_property/find_prospector");
	private static final Identifier TOW_PROSPECTOR = directive("company_property/tow_prospector");
	private static final Identifier RESTORE_PROSPECTOR = directive("company_property/restore_prospector");
	private static final Identifier REACH_WORKINGS_FLOOR = directive("company_property/reach_workings_floor");
	/** Layer 1, the Claim, and layer 2, the Old Workings. */
	private static final int THE_CLAIM = 1;
	private static final int THE_OLD_WORKINGS = 2;
	/** The zone at the bottom of a layer: the Deep Claim in layer 1, Prospector's Run in layer 2. */
	private static final int FLOOR_ZONE = Zones.COUNT - 1;

	private HandbookTriggers() {
	}

	private static Identifier directive(String path) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/" + path);
	}

	static void init() {
		TerminalEvents.REPAIRED.register((server, type, charter, player) -> onRepaired(type, player));
		TerminalEvents.ACTED.register((server, type, player, action) -> onActed(type, player, action));
		PodEvents.AFTER_TICK.register(HandbookTriggers::onPodTick);
		UpgradeEvents.BOUGHT.register((server, player, pod, track, tier) -> {
			if (track == ComponentTrack.SCANNER) {
				Directives.fire(player, INSTALL_SCANNER);
			}
		});
		BreachEvents.CROSSED.register(HandbookTriggers::onCrossed);
		HangarEvents.RESTORED.register((server, player, pod) -> {
			if (pod.chassis().equals(Chassis.PROSPECTOR)) {
				Directives.fire(player, RESTORE_PROSPECTOR);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % HandbookTuning.DEFAULT.triggerPollTicks() == 0) {
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					creditRepairs(server, player);
					pollPlayer(server, player);
				}
			}
		});
	}

	private static void onRepaired(TerminalType type, ServerPlayer player) {
		if (type == TerminalTypes.FUEL_PUMP) {
			Directives.fire(player, REPAIR_FUEL_PUMP);
		} else if (type == TerminalTypes.ORE_PROCESSOR) {
			Directives.fire(player, REPAIR_ORE_PROCESSOR);
		} else if (type == TerminalTypes.UPGRADE_TERMINAL) {
			Directives.fire(player, REPAIR_UPGRADE_TERMINAL);
		} else if (type == HangarTerminal.TYPE) {
			Directives.fire(player, REPAIR_MOLE);
		}
	}

	private static void onActed(TerminalType type, ServerPlayer player, Identifier action) {
		if (type == TerminalTypes.FUEL_PUMP && (action.equals(FuelPump.BUY) || action.equals(FuelPump.FILL))) {
			Directives.fire(player, REFUEL_MOLE);
		} else if (type == TerminalTypes.ORE_PROCESSOR && (action.equals(OreProcessor.SELL_CARGO) || action.equals(OreProcessor.SELL_INVENTORY))) {
			Directives.fire(player, SELL_ORE);
		} else if (type == TerminalTypes.UPGRADE_TERMINAL && action.equals(UpgradeTerminal.BUY)) {
			// The terminal installs what it sells, in the same action.
			Directives.fire(player, BUY_COMPONENT);
			Directives.fire(player, INSTALL_COMPONENT);
		} else if (type == HangarTerminal.TYPE && action.equals(HangarTerminal.BUY_MOLE)) {
			// A charter that came after the founding charter repairs nothing: it buys a refurbished Mole.
			Directives.fire(player, REPAIR_MOLE);
		} else if (type == TerminalTypes.REPAIR_STATION && (action.equals(RepairStation.REPAIR) || action.equals(RepairStation.REPAIR_TOTAL))) {
			Directives.fire(player, REPAIR_HULL);
		}
	}

	/** The first descent of a player from the Claim into the Old Workings. A vehicle's other riders are crossed on their own call. */
	private static void onCrossed(Entity entity, ServerLevel from, ServerLevel to, int fromLayer, int toLayer) {
		if (entity instanceof ServerPlayer player && fromLayer == THE_CLAIM && toLayer == THE_OLD_WORKINGS) {
			Directives.fire(player, BREACH_WORKINGS);
		}
	}

	/**
	 * Completes, for {@code player}'s charter, the repair directive of each terminal that is already repaired and that the charter
	 * has not done. Public so that a test can credit one player, and not every player on the server, from a repair state it
	 * installs for one tick. Does nothing while the saved repairs, charters or progress are unreadable.
	 */
	public static void creditRepairs(MinecraftServer server, ServerPlayer player) {
		RepairState repairs = RepairState.get(server);
		if (!repairs.isReadable()) {
			return;
		}
		Set<Identifier> done = HandbookProgress.completedFor(server, player.getUUID());
		creditRepair(repairs, player, done, TerminalTypes.FUEL_PUMP, REPAIR_FUEL_PUMP);
		creditRepair(repairs, player, done, TerminalTypes.ORE_PROCESSOR, REPAIR_ORE_PROCESSOR);
		creditRepair(repairs, player, done, TerminalTypes.UPGRADE_TERMINAL, REPAIR_UPGRADE_TERMINAL);
	}

	/**
	 * Completes, for {@code player}'s charter, the directives that a place makes true: standing in the Deep Claim, coming within
	 * {@link HandbookTuning#findProspectorBlocks()} blocks of a Prospector wreck, and being in a Prospector at the floor of the Old
	 * Workings. Public so that a test can poll one player. Does nothing on the surface, and nothing while the saved progress is
	 * unreadable; the reads for the other places wait until the player is in a layer.
	 */
	public static void pollPlayer(MinecraftServer server, ServerPlayer player) {
		ServerLevel level = player.level();
		Optional<Zones.Zone> zone = Zones.of(level, player.getBlockY());
		if (zone.isEmpty()) {
			return;
		}
		Set<Identifier> done = HandbookProgress.completedFor(server, player.getUUID());
		boolean onTheFloor = zone.get().index() == FLOOR_ZONE;
		fireOnce(player, done, REACH_DEEP_CLAIM, onTheFloor && zone.get().layer() == THE_CLAIM);
		fireOnce(player, done, REACH_WORKINGS_FLOOR, onTheFloor && zone.get().layer() == THE_OLD_WORKINGS
				&& player.getVehicle() instanceof PodEntity pod && pod.chassis().equals(Chassis.PROSPECTOR));
		if (!done.contains(FIND_PROSPECTOR) && prospectorWreckNear(level, player)) {
			Directives.fire(player, FIND_PROSPECTOR);
		}
	}

	private static boolean prospectorWreckNear(ServerLevel level, ServerPlayer player) {
		return !level.getEntitiesOfClass(PodEntity.class, player.getBoundingBox().inflate(HandbookTuning.DEFAULT.findProspectorBlocks()),
				pod -> pod.chassis().equals(Chassis.PROSPECTOR) && Wrecks.isWreck(pod)).isEmpty();
	}

	private static void creditRepair(RepairState repairs, ServerPlayer player, Set<Identifier> done, TerminalType type, Identifier directive) {
		if (!done.contains(directive) && repairs.repaired(type)) {
			Directives.fire(player, directive);
		}
	}

	private static void onPodTick(PodEntity pod) {
		if (pod.tickCount % HandbookTuning.DEFAULT.triggerPollTicks() != 0) {
			return;
		}
		if (pod.chassis().equals(Chassis.PROSPECTOR) && PodTowing.isTowed(pod)) {
			towedProspector(pod);
		}
		// The scan reads thousands of blocks: it runs once for the pod, and only when a rider's charter still lacks the directive.
		Supplier<Boolean> seesOre = Suppliers.memoize(() -> ScanSlice.hasOre(new LoadedBlocks(pod.level()), pod));
		for (Entity passenger : pod.getPassengers()) {
			if (passenger instanceof ServerPlayer player) {
				pilotedPod(pod, player, seesOre);
			}
		}
	}

	private static void pilotedPod(PodEntity pod, ServerPlayer player, Supplier<Boolean> seesOre) {
		MinecraftServer server = player.level().getServer();
		Set<Identifier> done = HandbookProgress.completedFor(server, player.getUUID());
		if (!done.contains(FIND_ORE) && seesOre.get()) {
			Directives.fire(player, FIND_ORE);
		}
		if (pod.chassis().equals(Chassis.MOLE)) {
			pilotedMole(pod, player, done);
		}
	}

	/** A Prospector on a cable inside the colony: the players riding the pod that tows it have towed it home. */
	private static void towedProspector(PodEntity towed) {
		MinecraftServer server = towed.level().getServer();
		Optional<ColonySite.Placed> colony = Colony.placed(server);
		Optional<PodEntity> tower = PodTowing.tower(towed);
		if (colony.isEmpty() || tower.isEmpty() || !inColony(towed, colony.get())) {
			return;
		}
		for (Entity passenger : tower.get().getPassengers()) {
			if (passenger instanceof ServerPlayer player) {
				fireOnce(player, HandbookProgress.completedFor(server, player.getUUID()), TOW_PROSPECTOR, true);
			}
		}
	}

	private static void pilotedMole(PodEntity pod, ServerPlayer player, Set<Identifier> done) {
		MinecraftServer server = player.level().getServer();
		fireOnce(player, done, BOARD_MOLE, true);
		fireOnce(player, done, FLY_MOLE, pod.flying());
		Optional<ColonySite.Placed> colony = Colony.placed(server);
		if (colony.isEmpty()) {
			return;
		}
		fireOnce(player, done, DRILL_DOWN, pod.drilling() && pod.drillDirection() == Direction.DOWN && deepEnough(pod, colony.get()));
		fireOnce(player, done, RETURN_TO_COLONY, done.contains(DRILL_DOWN) && inColony(pod, colony.get()));
	}

	private static void fireOnce(ServerPlayer player, Set<Identifier> done, Identifier directive, boolean happened) {
		if (happened && !done.contains(directive)) {
			Directives.fire(player, directive);
		}
	}

	/** Below the colony's ground by the tuned depth, or in a layer under the overworld, which is deeper still. */
	private static boolean deepEnough(PodEntity pod, ColonySite.Placed colony) {
		return pod.level().dimension() != Level.OVERWORLD || pod.getY() <= colony.groundY() - HandbookTuning.DEFAULT.drillDownBlocks();
	}

	/** On the colony's pad: inside its square, from just under the ground to the height it is cleared to. */
	private static boolean inColony(PodEntity pod, ColonySite.Placed colony) {
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		return pod.level().dimension() == Level.OVERWORLD
				&& Math.abs(pod.getX() - colony.center().getX()) <= half
				&& Math.abs(pod.getZ() - colony.center().getZ()) <= half
				&& pod.getY() >= colony.groundY() - 1
				&& pod.getY() <= colony.groundY() + ColonyTuning.DEFAULT.clearHeight();
	}
}
