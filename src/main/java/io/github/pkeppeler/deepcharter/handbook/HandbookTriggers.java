package io.github.pkeppeler.deepcharter.handbook;

import java.util.Optional;
import java.util.Set;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTerminal;

/**
 * Completes the directives of chapters 1 to 5 from what happens in the game, through {@link Directives#fire}. The directives of
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

	private HandbookTriggers() {
	}

	private static Identifier directive(String path) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/" + path);
	}

	static void init() {
		TerminalEvents.REPAIRED.register((server, type, charter, player) -> onRepaired(type, player));
		TerminalEvents.ACTED.register((server, type, player, action) -> onActed(type, player, action));
		PodEvents.AFTER_TICK.register(HandbookTriggers::onPodTick);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % HandbookTuning.DEFAULT.triggerPollTicks() == 0) {
				creditRepairs(server);
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
		}
	}

	/** Credits every online player, see {@link #creditRepairs(MinecraftServer, ServerPlayer)}. */
	private static void creditRepairs(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			creditRepairs(server, player);
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

	/** Skeleton for #84. */
	public static void pollPlayer(MinecraftServer server, ServerPlayer player) {
	}

	private static void creditRepair(RepairState repairs, ServerPlayer player, Set<Identifier> done, TerminalType type, Identifier directive) {
		if (!done.contains(directive) && repairs.repaired(type)) {
			Directives.fire(player, directive);
		}
	}

	private static void onPodTick(PodEntity pod) {
		if (pod.tickCount % HandbookTuning.DEFAULT.triggerPollTicks() != 0 || !pod.chassis().equals(Chassis.MOLE)) {
			return;
		}
		for (Entity passenger : pod.getPassengers()) {
			if (passenger instanceof ServerPlayer player) {
				pilotedMole(pod, player);
			}
		}
	}

	private static void pilotedMole(PodEntity pod, ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Set<Identifier> done = HandbookProgress.completedFor(server, player.getUUID());
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
