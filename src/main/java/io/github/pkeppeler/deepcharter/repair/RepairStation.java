package io.github.pkeppeler.deepcharter.repair;

import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * The repair station terminal's actions: hull repair at {@link RepairTuning#repairCostPerHp} dollars a point, and the shop. Both
 * charge the player's charter account, and a refused action charges nothing. The terminal framework has already checked that the
 * station is repaired and the player is in reach; the arguments are the client's and are checked here.
 */
public final class RepairStation {
	/** Repair {@code args.hp} hull points (a whole number above 0), or what is missing if that is less. */
	public static final Identifier REPAIR = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "repair");
	/** Repair all that is missing. */
	public static final Identifier REPAIR_TOTAL = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "repair_total");
	/** Buy one of the item whose id is {@code args.item}. */
	public static final Identifier BUY = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "buy");
	public static final String HP_KEY = "hp";
	public static final String ITEM_KEY = "item";
	private static final double HULL_PRECISION = 1000.0;

	private RepairStation() {
	}

	static void register() {
		TerminalActions.register(TerminalTypes.REPAIR_STATION, REPAIR, context -> repair(context, context.args().getInt(HP_KEY)));
		TerminalActions.register(TerminalTypes.REPAIR_STATION, REPAIR_TOTAL, context -> repair(context, Optional.of(Integer.MAX_VALUE)));
		TerminalActions.register(TerminalTypes.REPAIR_STATION, BUY, RepairStation::buy);
	}

	private static Optional<Component> repair(TerminalAction.Context context, Optional<Integer> requested) {
		if (requested.isEmpty() || requested.get() <= 0) {
			return Consumables.refusal("bad_amount");
		}
		Optional<PodEntity> parked = parkedPod(context);
		if (parked.isEmpty()) {
			return Consumables.refusal("no_pod");
		}
		PodEntity pod = parked.get();
		// Only the hangar restores a wreck (#77): a pod at hull 0 is refused, however much is paid.
		if (pod.hull() <= 0f || Wrecks.isWreck(pod)) {
			return Consumables.refusal("wreck");
		}
		// Hull is a float: the gap is rounded to a thousandth of a point, so float noise cannot add a dollar.
		double missing = Math.round(((double) pod.maxHull() - pod.hull()) * HULL_PRECISION) / HULL_PRECISION;
		if (missing <= 0.0) {
			return Consumables.refusal("hull_full");
		}
		double repaired = Math.min(requested.get(), missing);
		// A part of a hull point is charged as a whole dollar, rounded up.
		long cost = (long) Math.ceil(repaired * RepairTuning.DEFAULT.repairCostPerHp());
		Optional<CharterRefusal> unpaid = Charters.spend(context.server(), context.charter().orElseThrow().id(), cost);
		if (unpaid.isPresent()) {
			return Optional.of(unpaid.get().message());
		}
		pod.setHull(pod.hull() + (float) repaired);
		pod.level().playSound(null, pod.getX(), pod.getY(), pod.getZ(), DeepSound.REPAIR_NANOBOTS.event(), SoundSource.PLAYERS);
		return Optional.empty();
	}

	private static Optional<Component> buy(TerminalAction.Context context) {
		Optional<Consumable> item = context.args().getString(ITEM_KEY).map(Identifier::tryParse).flatMap(Consumable::byItemId);
		if (item.isEmpty()) {
			return Consumables.refusal("unknown_item");
		}
		ServerPlayer player = context.player();
		ItemStack stack = new ItemStack(RepairRegistry.item(item.get()));
		Inventory inventory = player.getInventory();
		// Checked first: a creative player's add() discards what does not fit, and the money is not given back.
		if (inventory.getSlotWithRemainingSpace(stack) < 0 && inventory.getFreeSlot() < 0) {
			return Consumables.refusal("no_room");
		}
		Optional<CharterRefusal> unpaid = Charters.spend(context.server(), context.charter().orElseThrow().id(), item.get().price());
		if (unpaid.isPresent()) {
			return Optional.of(unpaid.get().message());
		}
		inventory.add(stack);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), DeepSound.UI_PURCHASE.event(), SoundSource.PLAYERS);
		return Optional.empty();
	}

	/** The nearest pod parked at the station ({@link Terminals#parkedPods}) that the player's charter may use. */
	private static Optional<PodEntity> parkedPod(TerminalAction.Context context) {
		return Terminals.parkedPods(context.player().level(), context.pos(), context.charter()).stream().findFirst();
	}
}
