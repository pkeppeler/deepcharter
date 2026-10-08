package io.github.pkeppeler.deepcharter.terminal;

import java.util.List;
import java.util.Optional;

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
 * @param feature  what the type adds ({@link TerminalFeatures}): present for a repaired terminal of a type that has one
 */
public record TerminalView(BlockPos pos, Identifier type, boolean repaired, boolean unlocked, List<PartStatus> parts, Optional<TerminalFeature> feature) {
	public static final StreamCodec<ByteBuf, TerminalView> STREAM_CODEC = new StreamCodec<>() {
		@Override
		public TerminalView decode(ByteBuf buffer) {
			BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
			Identifier type = Identifier.STREAM_CODEC.decode(buffer);
			boolean repaired = ByteBufCodecs.BOOL.decode(buffer);
			boolean unlocked = ByteBufCodecs.BOOL.decode(buffer);
			List<PartStatus> parts = PartStatus.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buffer);
			return new TerminalView(pos, type, repaired, unlocked, parts, TerminalFeatures.codec(type).decode(buffer));
		}

		@Override
		public void encode(ByteBuf buffer, TerminalView view) {
			BlockPos.STREAM_CODEC.encode(buffer, view.pos);
			Identifier.STREAM_CODEC.encode(buffer, view.type);
			ByteBufCodecs.BOOL.encode(buffer, view.repaired);
			ByteBufCodecs.BOOL.encode(buffer, view.unlocked);
			PartStatus.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buffer, view.parts);
			TerminalFeatures.codec(view.type).encode(buffer, view.feature);
		}
	};

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

	/** This view with {@code feature}. */
	TerminalView withFeature(Optional<TerminalFeature> feature) {
		return new TerminalView(pos, type, repaired, unlocked, parts, feature);
	}

	/** The feature if the view carries one of class {@code kind}. */
	public <T extends TerminalFeature> Optional<T> feature(Class<T> kind) {
		return feature.filter(kind::isInstance).map(kind::cast);
	}

	/** A terminal that needs no repair is always online, whatever the state. */
	static TerminalView of(BlockPos pos, TerminalType type, RepairState state) {
		if (!type.needsRepair()) {
			return new TerminalView(pos.immutable(), type.id(), true, true, List.of(), Optional.empty());
		}
		boolean repaired = state.repaired(type);
		boolean unlocked = repaired || type.prerequisite().map(state::repaired).orElse(true);
		List<Identifier> inserted = state.inserted(type).stream().map(TerminalType::partId).toList();
		List<PartStatus> parts = type.parts().stream()
				.map(part -> new PartStatus(TerminalType.partId(part), inserted.contains(TerminalType.partId(part)))).toList();
		return new TerminalView(pos.immutable(), type.id(), repaired, unlocked, parts, Optional.empty());
	}
}
