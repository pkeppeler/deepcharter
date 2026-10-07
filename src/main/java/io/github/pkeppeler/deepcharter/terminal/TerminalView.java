package io.github.pkeppeler.deepcharter.terminal;

import java.util.List;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * What a client needs to draw one terminal, as the server sees it now.
 *
 * @param type     the terminal type id
 * @param repaired the terminal is online
 * @param unlocked its prerequisite is repaired, so parts can go in. Always true for a repaired terminal.
 * @param parts    each of the type's parts, in the type's order, and whether it is in
 */
public record TerminalView(BlockPos pos, Identifier type, boolean repaired, boolean unlocked, List<PartStatus> parts) {
	public static final StreamCodec<ByteBuf, TerminalView> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, TerminalView::pos,
			Identifier.STREAM_CODEC, TerminalView::type,
			ByteBufCodecs.BOOL, TerminalView::repaired,
			ByteBufCodecs.BOOL, TerminalView::unlocked,
			PartStatus.STREAM_CODEC.apply(ByteBufCodecs.list()), TerminalView::parts,
			TerminalView::new);

	/** One part of a terminal, by item id. */
	public record PartStatus(Identifier part, boolean inserted) {
		public static final StreamCodec<ByteBuf, PartStatus> STREAM_CODEC = StreamCodec.composite(
				Identifier.STREAM_CODEC, PartStatus::part,
				ByteBufCodecs.BOOL, PartStatus::inserted,
				PartStatus::new);
	}

	public TerminalView {
		parts = List.copyOf(parts);
	}

	/** A terminal that needs no repair is always online, whatever the state. */
	static TerminalView of(BlockPos pos, TerminalType type, RepairState state) {
		if (!type.needsRepair()) {
			return new TerminalView(pos.immutable(), type.id(), true, true, List.of());
		}
		boolean repaired = state.repaired(type);
		boolean unlocked = repaired || type.prerequisite().map(state::repaired).orElse(true);
		List<Identifier> inserted = state.inserted(type).stream().map(TerminalType::partId).toList();
		List<PartStatus> parts = type.parts().stream()
				.map(part -> new PartStatus(TerminalType.partId(part), inserted.contains(TerminalType.partId(part)))).toList();
		return new TerminalView(pos.immutable(), type.id(), repaired, unlocked, parts);
	}
}
