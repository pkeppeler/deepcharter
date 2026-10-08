package io.github.pkeppeler.deepcharter.market;

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
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeatures;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/**
 * The work orders of the ore processor terminal (SPEC section 4, Economy). A charter hands in the ore an order asks for from what the
 * player carries; the amount still owed is taken, no more. Handing in the last of it completes the order: it pays the reward and
 * has its effect ({@link WorkOrder}), once, because a finished order takes nothing more.
 *
 * <p>Every refusal comes before any ore is taken, including a reward that a full account could not take. The reward is paid last.
 * The terminal has already checked the player's range, the charter and the repair state.
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

	/** Hands in the ore of {@code order} that the player carries, up to what is still owed. Empty when done, or why it was refused. */
	public static Optional<Component> deliver(TerminalAction.Context context, WorkOrder order) {
		MinecraftServer server = context.server();
		Charter charter = context.charter().orElseThrow();
		WorkOrderData data = WorkOrderData.get(server);
		if (!data.isReadable()) {
			logUnreadable(data);
			return Optional.of(Component.translatable("deepcharter.market.refusal.work_orders_unreadable"));
		}
		int owed = order.quantity() - data.delivered(charter.id(), order);
		if (owed <= 0) {
			return Optional.of(Component.translatable("deepcharter.market.refusal.order_done"));
		}
		Inventory inventory = context.player().getInventory();
		int amount = Math.min(owed, carried(inventory, order));
		if (amount == 0) {
			return Optional.of(Component.translatable("deepcharter.market.refusal.no_order_ore", oreName(order)));
		}
		boolean completes = amount == owed;
		if (completes) {
			if (FounderStatue.handPositions(server).isEmpty()) {
				return Optional.of(Component.translatable("deepcharter.market.refusal.no_statue"));
			}
			if (charter.account() > Long.MAX_VALUE - order.reward()) {
				return Optional.of(CharterRefusal.ACCOUNT_FULL.message());
			}
		}
		// Nothing below this line may refuse: the ore is taken, the progress recorded, and the reward paid last.
		take(inventory, order, amount);
		data.add(charter.id(), order, amount);
		if (completes) {
			Charters.deposit(server, charter.id(), order.reward()).ifPresent(refusal -> {
				DeepCharter.LOGGER.error("The reward of work order {} was checked and still refused ({}); the order is complete without it", order, refusal);
			});
			complete(server, order);
		}
		context.player().sendOverlayMessage(completes
				? Component.translatable("deepcharter.market.order_completed", Component.translatable(order.titleKey()), order.reward())
				: Component.translatable("deepcharter.market.order_delivered", amount, oreName(order), owed - amount));
		return Optional.empty();
	}

	private static void complete(MinecraftServer server, WorkOrder order) {
		switch (order) {
			case FOUNDERS_HANDS -> FounderStatue.restoreHands(server);
		}
	}

	private static Component oreName(WorkOrder order) {
		return Component.translatable(OreRegistry.item(order.ore()).getDescriptionId());
	}

	private static int carried(Inventory inventory, WorkOrder order) {
		return inventory.countItem(OreRegistry.item(order.ore()));
	}

	/** Takes {@code amount} of the order's ore, which the caller has counted. */
	private static void take(Inventory inventory, WorkOrder order, int amount) {
		int left = amount;
		for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(OreRegistry.item(order.ore()))) {
				left -= inventory.removeItem(slot, left).getCount();
			}
		}
		if (left != 0) {
			throw new IllegalStateException("counted " + amount + " " + order.ore() + " and could not take " + left + " of them");
		}
	}

	private static void logUnreadable(WorkOrderData data) {
		if (data.firstUnreadableReport()) {
			DeepCharter.LOGGER.error("The saved work orders have version {} that this build cannot read: no work order takes ore until the world is opened by a build that reads them",
					data.unreadableVersion().orElse("?"));
		}
	}

	/** The work orders of the player's charter, for the processor's screen. Never throws on unreadable saved data. */
	public static WorkOrdersView view(MinecraftServer server, ServerPlayer player, Optional<Charter> charter, BlockPos pos) {
		WorkOrderData data = WorkOrderData.get(server);
		if (!data.isReadable()) {
			logUnreadable(data);
			return new WorkOrdersView(false, List.of());
		}
		Charter own = charter.orElseThrow();
		return new WorkOrdersView(true, Arrays.stream(WorkOrder.values()).map(order -> new WorkOrdersView.Entry(order, data.delivered(own.id(), order))).toList());
	}
}
