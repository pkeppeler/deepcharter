package io.github.pkeppeler.deepcharter.handbook;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import io.github.pkeppeler.deepcharter.attachment.Versioned;

/**
 * Which handbook entries (chapters, notes) one player has read. Unlike directive progress it belongs to the player, not the
 * charter: a note a crewmate found is unread for you. The state is the player attachment {@link HandbookRegistry#READ_MARKS}, kept
 * on death and sent to its owner only.
 */
public record ReadMarks(Set<Identifier> read) {
	public static final int VERSION = 1;
	public static final ReadMarks DEFAULT = new ReadMarks(Set.of());
	public static final MapCodec<ReadMarks> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Identifier.CODEC.listOf().xmap(Set::copyOf, ReadMarks::sorted).fieldOf("read").forGetter(ReadMarks::read)).apply(instance, ReadMarks::new));
	public static final StreamCodec<ByteBuf, ReadMarks> STREAM = Identifier.STREAM_CODEC.apply(ByteBufCodecs.list())
			.map(read -> new ReadMarks(Set.copyOf(read)), marks -> sorted(marks.read()));
	/** The disk codec of the attachment, which never fails to decode. */
	public static final Codec<Versioned<ReadMarks>> CODEC = Versioned.codec(VERSION, BODY);

	public ReadMarks {
		read = Set.copyOf(read);
	}

	public static boolean isRead(Player player, Identifier entry) {
		return Versioned.require(player, HandbookRegistry.READ_MARKS).read().contains(entry);
	}

	/** Marks {@code entry} read for {@code player}. Marking it again changes nothing. */
	public static void mark(Player player, Identifier entry) {
		Versioned.modify(player, HandbookRegistry.READ_MARKS, marks -> {
			Set<Identifier> read = new HashSet<>(marks.read());
			read.add(entry);
			return new ReadMarks(read);
		});
	}

	/** Marks {@code entry} unread again for {@code player}. */
	public static void unmark(Player player, Identifier entry) {
		Versioned.modify(player, HandbookRegistry.READ_MARKS, marks -> {
			Set<Identifier> read = new HashSet<>(marks.read());
			read.remove(entry);
			return new ReadMarks(read);
		});
	}

	private static List<Identifier> sorted(Set<Identifier> read) {
		return read.stream().sorted().toList();
	}
}
