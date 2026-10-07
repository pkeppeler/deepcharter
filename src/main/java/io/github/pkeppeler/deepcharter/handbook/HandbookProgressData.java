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
 * The directives each charter has completed, saved with the world and keyed by {@link CharterId}, so a charter keeps its progress
 * when it goes dormant. It only records: {@link HandbookProgress} is what the rest of the mod calls.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and every use
 * of it throws, so a newer world is never overwritten by an older build (see ADR 0007).
 */
public final class HandbookProgressData extends SavedData {
	public static final int VERSION = 1;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook_progress");
	private static final Codec<Versioned<List<Entry>>> VERSIONED_CODEC = Versioned.codec(VERSION, Entry.CODEC.listOf().fieldOf("progress"));

	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<HandbookProgressData> CODEC = VERSIONED_CODEC.xmap(HandbookProgressData::new, HandbookProgressData::versioned);
	// Datafixer type: vanilla applies it to saved data it reads. Our data has a version of its own, so the vanilla fixers find nothing to fix.
	public static final SavedDataType<HandbookProgressData> TYPE = new SavedDataType<>(ID, HandbookProgressData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<CharterId, Set<Identifier>> progress = new LinkedHashMap<>();
	private final Optional<Versioned.Unreadable<List<Entry>>> unreadable;

	public HandbookProgressData() {
		this.unreadable = Optional.empty();
	}

	private HandbookProgressData(Versioned<List<Entry>> loaded) {
		switch (loaded) {
			case Versioned.Readable<List<Entry>> readable -> {
				readable.value().forEach(entry -> progress.put(entry.charter(), new LinkedHashSet<>(entry.completed())));
				unreadable = Optional.empty();
			}
			case Versioned.Unreadable<List<Entry>> raw -> unreadable = Optional.of(raw);
		}
	}

	/** The world's handbook progress. Call on the server thread. */
	public static HandbookProgressData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	/** The directives {@code charter} has completed. Throws if the saved data is of a version this build cannot read. */
	public Set<Identifier> completed(CharterId charter) {
		return Set.copyOf(readable().getOrDefault(charter, Set.of()));
	}

	/** Records {@code directive} as completed for {@code charter}. Returns false when it already was. */
	public boolean complete(CharterId charter, Identifier directive) {
		boolean added = readable().computeIfAbsent(charter, ignored -> new LinkedHashSet<>()).add(directive);
		if (added) {
			setDirty();
		}
		return added;
	}

	private Versioned<List<Entry>> versioned() {
		return unreadable.<Versioned<List<Entry>>>map(raw -> raw)
				.orElseGet(() -> Versioned.of(progress.entrySet().stream().map(entry -> new Entry(entry.getKey(), List.copyOf(entry.getValue()))).toList()));
	}

	private Map<CharterId, Set<Identifier>> readable() {
		if (unreadable.isPresent()) {
			throw new IllegalStateException("the saved handbook progress has version " + unreadable.get().version()
					+ " that this build cannot read (it reads " + VERSION + ")");
		}
		return progress;
	}

	/** One charter's completed directives, in the order they were completed. */
	private record Entry(CharterId charter, List<Identifier> completed) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				CharterId.CODEC.fieldOf("charter").forGetter(Entry::charter),
				Identifier.CODEC.listOf().fieldOf("completed").forGetter(Entry::completed)).apply(instance, Entry::new));
	}
}
