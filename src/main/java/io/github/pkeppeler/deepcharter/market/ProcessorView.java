package io.github.pkeppeler.deepcharter.market;

import java.util.List;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeature;

/**
 * What the ore processor adds to its view: the charter's work orders and the ore in the cargo of the pods parked at it, which the
 * WITHDRAW ORE screen lists. The server decides every action; this only tells the screen what to offer.
 *
 * @param orders the work orders
 * @param cargo  the ore the readable pods the player may use hold between them, by ore in the enum's order; an ore none holds is absent
 */
public record ProcessorView(WorkOrdersView orders, List<Hold> cargo) implements TerminalFeature {
	public static final StreamCodec<ByteBuf, ProcessorView> STREAM_CODEC = StreamCodec.composite(
			WorkOrdersView.STREAM_CODEC, ProcessorView::orders,
			Hold.STREAM_CODEC.apply(ByteBufCodecs.list()), ProcessorView::cargo,
			ProcessorView::new);

	/** How many of one ore the parked pods hold. */
	public record Hold(OreType ore, int count) {
		public static final StreamCodec<ByteBuf, Hold> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT.map(ordinal -> OreType.values()[ordinal], OreType::ordinal), Hold::ore,
				ByteBufCodecs.VAR_INT, Hold::count,
				Hold::new);
	}

	public ProcessorView {
		cargo = List.copyOf(cargo);
	}
}
