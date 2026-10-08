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

	/** One order, how much of the current round the charter has handed in, and how many rounds it has completed. */
	public record Entry(WorkOrder order, int delivered, int rounds) {
		public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
				WorkOrder.STREAM_CODEC, Entry::order,
				ByteBufCodecs.VAR_INT, Entry::delivered,
				ByteBufCodecs.VAR_INT, Entry::rounds,
				Entry::new);

		/** True for a one-shot order that is finished. A repeatable order is never done. */
		public boolean done() {
			return !order.repeatable() && delivered >= order.quantity();
		}
	}

	public WorkOrdersView {
		orders = List.copyOf(orders);
	}
}
