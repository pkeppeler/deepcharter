package io.github.pkeppeler.deepcharter.market;

import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;

/** The work orders of the ore processor terminal. */
public final class WorkOrders {
	/** The action: hand in the ore of the order named by {@link #ORDER_KEY}. */
	public static final Identifier DELIVER = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "work_order_deliver");
	/** The id of a {@link WorkOrder}, such as {@code deepcharter:founders_hands}. */
	public static final String ORDER_KEY = "order";

	private WorkOrders() {
	}

	public static Optional<Component> deliver(TerminalAction.Context context, WorkOrder order) {
		throw new UnsupportedOperationException("stub: deliver");
	}

	public static WorkOrdersView view(MinecraftServer server, ServerPlayer player, Optional<Charter> charter, BlockPos pos) {
		throw new UnsupportedOperationException("stub: view");
	}
}
