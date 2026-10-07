package io.github.pkeppeler.deepcharter.handbook;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;

/**
 * The Notes each charter has found, saved with the world and keyed by {@link CharterId}, so a charter keeps its Notes when it goes
 * dormant. It only records: {@link Notes} is what the rest of the mod calls. Who has read a Note is not here: that belongs to the
 * player ({@link ReadMarks}).
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and every use
 * of it throws, so a newer world is never overwritten by an older build (see ADR 0007).
 */
public final class NotesData extends SavedData {
	public static final int VERSION = 1;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook_notes");
	private static final Codec<Versioned<List<Entry>>> VERSIONED_CODEC = Versioned.codec(VERSION, Entry.CODEC.listOf().fieldOf("notes"));

	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<NotesData> CODEC = VERSIONED_CODEC.xmap(NotesData::new, NotesData::versioned);
	// Datafixer type: vanilla applies it to saved data it reads. Our data has a version of its own, so the vanilla fixers find nothing to fix.
	public static final SavedDataType<NotesData> TYPE = new SavedDataType<>(ID, NotesData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<CharterId, Set<Identifier>> found = new LinkedHashMap<>();
	private final Optional<Versioned.Unreadable<List<Entry>>> unreadable;

	public NotesData() {
		this.unreadable = Optional.empty();
	}

	private NotesData(Versioned<List<Entry>> loaded) {
		switch (loaded) {
			case Versioned.Readable<List<Entry>> readable -> {
				readable.value().forEach(entry -> found.put(entry.charter(), new LinkedHashSet<>(entry.notes())));
				unreadable = Optional.empty();
			}
			case Versioned.Unreadable<List<Entry>> raw -> unreadable = Optional.of(raw);
		}
	}

	/** The world's Notes. Call on the server thread. */
	public static NotesData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	/** False when the saved Notes are of a version this build cannot read: {@link #found} and {@link #add} then throw. */
	public boolean isReadable() {
		return unreadable.isEmpty();
	}

	/** The Notes {@code charter} has found. Throws if the saved data is of a version this build cannot read. */
	public Set<Identifier> found(CharterId charter) {
		return Set.copyOf(readable().getOrDefault(charter, Set.of()));
	}

	/** Records {@code note} as found by {@code charter}. Returns false when it already was. */
	public boolean add(CharterId charter, Identifier note) {
		boolean added = readable().computeIfAbsent(charter, ignored -> new LinkedHashSet<>()).add(note);
		if (added) {
			setDirty();
		}
		return added;
	}

	private Versioned<List<Entry>> versioned() {
		return unreadable.<Versioned<List<Entry>>>map(raw -> raw)
				.orElseGet(() -> Versioned.of(found.entrySet().stream().map(entry -> new Entry(entry.getKey(), List.copyOf(entry.getValue()))).toList()));
	}

	private Map<CharterId, Set<Identifier>> readable() {
		if (unreadable.isPresent()) {
			throw new IllegalStateException("the saved handbook notes have version " + unreadable.get().version()
					+ " that this build cannot read (it reads " + VERSION + ")");
		}
		return found;
	}

	/** One charter's found Notes, in the order they were found. */
	private record Entry(CharterId charter, List<Identifier> notes) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				CharterId.CODEC.fieldOf("charter").forGetter(Entry::charter),
				Identifier.CODEC.listOf().fieldOf("notes").forGetter(Entry::notes)).apply(instance, Entry::new));
	}
}
