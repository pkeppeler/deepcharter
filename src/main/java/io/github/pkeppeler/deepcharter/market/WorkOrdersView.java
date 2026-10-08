package io.github.pkeppeler.deepcharter.market;

import java.util.List;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import io.github.pkeppeler.deepcharter.terminal.TerminalFeature;

/**
 * The work orders as the ore processor's screen shows them, for the player's charter.
 *
 * @param readable false when the saved work orders are of a version this build cannot read: no order is listed then
 * @param orders   every {@link WorkOrder}, in the enum's order, when readable
 */
public record WorkOrdersView(boolean readable, List<Entry> orders) implements TerminalFeature {
	public static final StreamCodec<ByteBuf, WorkOrdersView> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, WorkOrdersView::readable,
			Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), WorkOrdersView::orders,
			WorkOrdersView::new);

	/** One order and how much of it the charter has handed in. */
	public record Entry(WorkOrder order, int delivered) {
		public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
				WorkOrder.STREAM_CODEC, Entry::order,
				ByteBufCodecs.VAR_INT, Entry::delivered,
				Entry::new);

		public boolean done() {
			return delivered >= order.quantity();
		}
	}

	public WorkOrdersView {
		orders = List.copyOf(orders);
	}
}
