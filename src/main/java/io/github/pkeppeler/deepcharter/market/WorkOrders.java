package io.github.pkeppeler.deepcharter.market;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.FounderStatue;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeatures;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;

/**
 * The work orders of the ore processor terminal (SPEC section 4, Economy). A charter hands in the ore an order asks for from what the
 * player carries and from the cargo hold of every pod parked at the processor that the charter may use ({@link Terminals#parkedPods},
 * the rule the cargo sale uses): the inventory first, then the holds nearest first. The amount still owed in the current round is
 * taken, no more. Handing in the last of it completes the round: it pays the reward and has the order's effect ({@link WorkOrder}).
 * A one-shot order then takes nothing more; a repeatable one opens its next round at once ({@link WorkOrderData#add}).
 *
 * <p>Every refusal comes before any ore is taken, including a reward that a full account could not take. The reward is paid last.
 * The terminal has already checked the player's range, the charter and the repair state. A hold whose saved cargo is unreadable is
 * skipped, and counts for nothing.
 */
public final class WorkOrders {
	/** The action: hand in the ore of the order named by {@link #ORDER_KEY}. */
	public static final Identifier DELIVER = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "work_order_deliver");
	/** The id of a {@link WorkOrder}, such as {@code deepcharter:founders_hands}. */
	public static final String ORDER_KEY = "order";

	private WorkOrders() {
	}

	static void register() {
		TerminalActions.register(TerminalTypes.ORE_PROCESSOR, DELIVER, WorkOrders::runDeliver);
		TerminalFeatures.register(TerminalTypes.ORE_PROCESSOR, WorkOrdersView.STREAM_CODEC, WorkOrders::view);
	}

	private static Optional<Component> runDeliver(TerminalAction.Context context) {
		Optional<WorkOrder> order = context.args().getString(ORDER_KEY).map(Identifier::tryParse).flatMap(WorkOrder::find);
		if (order.isEmpty()) {
			return Optional.of(Component.translatable("deepcharter.market.refusal.bad_order"));
		}
		return deliver(context, order.get());
	}

	/** True when {@code charter} is offered {@code order}: it has reached the layer the order opens at. */
	static boolean offered(MinecraftServer server, Charter charter, WorkOrder order) {
		return order.unlockLayer() == 0 || charter.deepestPoint() >= LayerChain.topDepth(server.registryAccess(), order.unlockLayer());
	}

	/**
	 * Hands in the ore of {@code order} from the player's inventory and the parked pods' cargo, up to what is still owed in the current
	 * round. Empty when done, or why it was refused.
	 */
	public static Optional<Component> deliver(TerminalAction.Context context, WorkOrder order) {
		MinecraftServer server = context.server();
		Charter charter = context.charter().orElseThrow();
		WorkOrderData data = WorkOrderData.get(server);
		if (!data.isReadable()) {
			return Optional.of(Component.translatable("deepcharter.market.refusal.work_orders_unreadable"));
		}
		if (!offered(server, charter, order)) {
			return Optional.of(Component.translatable("deepcharter.market.refusal.order_locked"));
		}
		int owed = order.quantity() - data.delivered(charter.id(), order);
		if (owed <= 0) {
			return Optional.of(Component.translatable("deepcharter.market.refusal.order_done"));
		}
		Inventory inventory = context.player().getInventory();
		int carried = inventory.countItem(OreRegistry.item(order.ore()));
		List<PodEntity> holds = new ArrayList<>();
		boolean skippedHold = false;
		for (PodEntity pod : Terminals.parkedPods(context.player().level(), context.pos(), Optional.of(charter))) {
			if (pod.cargo().isReadable()) {
				holds.add(pod);
			} else {
				skippedHold = true;
			}
		}
		int inHolds = holds.stream().mapToInt(pod -> pod.cargo().count(order.ore())).sum();
		if (carried + inHolds == 0) {
			return Optional.of(skippedHold ? Component.translatable("deepcharter.market.refusal.unreadable_cargo")
					: Component.translatable("deepcharter.market.refusal.no_order_ore", order.oreName()));
		}
		int amount = Math.min(owed, carried + inHolds);
		boolean completes = amount == owed;
		if (completes) {
			Optional<Component> refusal = refuseCompletion(server, charter, order);
			if (refusal.isPresent()) {
				return refusal;
			}
		}
		// Nothing below this line may refuse: the ore is taken, the progress recorded, and the reward paid last.
		take(inventory, holds, order, amount);
		data.add(charter.id(), order, amount);
		if (completes) {
			Charters.deposit(server, charter.id(), order.reward()).ifPresent(refusal -> {
				DeepCharter.LOGGER.error("The reward of work order {} was checked and still refused ({}); the round is complete without it", order, refusal);
			});
			complete(server, order);
		}
		context.player().sendOverlayMessage(completes
				? Component.translatable("deepcharter.market.order_completed", Component.translatable(order.titleKey()), order.reward())
				: Component.translatable("deepcharter.market.order_delivered", amount, order.oreName(), owed - amount));
		return Optional.empty();
	}

	/** Why the round that the next delivery completes cannot be completed, before any ore is taken. */
	private static Optional<Component> refuseCompletion(MinecraftServer server, Charter charter, WorkOrder order) {
		if (order == WorkOrder.FOUNDERS_HANDS && FounderStatue.handPositions(server).isEmpty()) {
			return Optional.of(Component.translatable("deepcharter.market.refusal.no_statue"));
		}
		if (charter.account() > Long.MAX_VALUE - order.reward()) {
			return Optional.of(CharterRefusal.ACCOUNT_FULL.message());
		}
		return Optional.empty();
	}

	private static void complete(MinecraftServer server, WorkOrder order) {
		switch (order) {
			case FOUNDERS_HANDS -> FounderStatue.restoreHands(server);
			case MORALE_INITIATIVE -> {
				// No effect beyond the reward: the canon gives the order no story beat of its own.
			}
		}
	}

	/** Takes {@code amount} of the order's ore, which the caller has counted: from the inventory first, then from the holds in order. */
	private static void take(Inventory inventory, List<PodEntity> holds, WorkOrder order, int amount) {
		int left = amount;
		for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(OreRegistry.item(order.ore()))) {
				left -= inventory.removeItem(slot, left).getCount();
			}
		}
		for (int hold = 0; hold < holds.size() && left > 0; hold++) {
			PodEntity pod = holds.get(hold);
			if (pod.cargo().count(order.ore()) > 0) {
				left -= pod.cargo().take(pod, order.ore(), left);
			}
		}
		if (left != 0) {
			throw new IllegalStateException("counted " + amount + " " + order.ore() + " and could not take " + left + " of them");
		}
	}

	/** The work orders of the player's charter that it is offered, for the processor's screen. Never throws on unreadable saved data. */
	public static WorkOrdersView view(MinecraftServer server, ServerPlayer player, Optional<Charter> charter, BlockPos pos) {
		WorkOrderData data = WorkOrderData.get(server);
		if (!data.isReadable()) {
			return new WorkOrdersView(false, List.of());
		}
		Charter own = charter.orElseThrow();
		return new WorkOrdersView(true, Arrays.stream(WorkOrder.values()).filter(order -> offered(server, own, order))
				.map(order -> new WorkOrdersView.Entry(order, data.delivered(own.id(), order), data.rounds(own.id(), order))).toList());
	}
}
