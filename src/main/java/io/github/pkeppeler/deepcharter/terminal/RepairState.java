package io.github.pkeppeler.deepcharter.terminal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.SavedState;
import io.github.pkeppeler.deepcharter.attachment.Versioned;

/**
 * Which parts are in which terminal, for the whole world: when any charter repairs a terminal, every charter can use it
 * (SPEC section 3). A terminal is repaired when every one of its parts is in. It holds the rules of the repair order and
 * only changes and saves state; {@link Terminals} adds the player, the inventory and the events.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and every
 * use of it throws, as for {@code CharterData} (ADR 0007). Parts are saved by item id, so a part that a later build removes
 * stays in the file.
 */
public final class RepairState extends SavedData {
	public static final int VERSION = 1;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "terminals");

	/** One terminal type and the ids of the parts inserted so far, in the order they went in. */
	private record Entry(Identifier type, List<Identifier> parts) {
		private static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Identifier.CODEC.fieldOf("type").forGetter(Entry::type),
				Identifier.CODEC.listOf().fieldOf("parts").forGetter(Entry::parts)).apply(instance, Entry::new));
	}

	private static final Codec<Versioned<List<Entry>>> VERSIONED_CODEC = Versioned.codec(VERSION, Entry.CODEC.listOf().fieldOf("terminals"));

	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<RepairState> CODEC = VERSIONED_CODEC.xmap(RepairState::new, RepairState::versioned);
	// Datafixer type: as for CharterData (ADR 0007), vanilla's fixers find nothing of theirs in a file that carries our own version.
	public static final SavedDataType<RepairState> TYPE = new SavedDataType<>(ID, RepairState::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final SavedState<Map<Identifier, List<Identifier>>> state;

	public RepairState() {
		this.state = SavedState.fresh(ID, VERSION, new LinkedHashMap<>());
	}

	private RepairState(Versioned<List<Entry>> loaded) {
		this.state = SavedState.load(ID, VERSION, loaded, entries -> {
			Map<Identifier, List<Identifier>> inserted = new LinkedHashMap<>();
			entries.forEach(entry -> inserted.put(entry.type(), new ArrayList<>(entry.parts())));
			return inserted;
		});
	}

	/** The world's repair state. Call on the server thread. */
	public static RepairState get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	private Versioned<List<Entry>> versioned() {
		return state.versioned(inserted ->
				inserted.entrySet().stream().map(entry -> new Entry(entry.getKey(), List.copyOf(entry.getValue()))).toList());
	}

	/** False (logged once) when the saved data is of a version this build cannot read. Gameplay code asks this and refuses, instead of calling what throws. */
	public boolean isReadable() {
		return state.isReadable();
	}

	private Map<Identifier, List<Identifier>> readable() {
		return state.orThrow();
	}

	private List<Identifier> insertedIds(TerminalType type) {
		return readable().getOrDefault(type.id(), List.of());
	}

	/** True when every part of {@code type} is in. */
	public boolean repaired(TerminalType type) {
		List<Identifier> ids = insertedIds(type);
		return type.parts().stream().allMatch(part -> ids.contains(TerminalType.partId(part)));
	}

	/** The parts of {@code type} that are in, in the order they went in. */
	public List<Item> inserted(TerminalType type) {
		return insertedIds(type).stream().flatMap(id -> BuiltInRegistries.ITEM.getOptional(id).stream()).toList();
	}

	/** Why {@code part} cannot go into {@code type} now, or empty when it can. Changes nothing. */
	public Optional<TerminalRefusal> check(TerminalType type, Item part) {
		if (!type.parts().contains(part)) {
			return Optional.of(TerminalRefusal.NOT_A_PART);
		}
		if (repaired(type)) {
			return Optional.of(TerminalRefusal.ALREADY_REPAIRED);
		}
		if (type.prerequisite().isPresent() && !repaired(type.prerequisite().get())) {
			return Optional.of(TerminalRefusal.PREREQUISITE_UNREPAIRED);
		}
		if (insertedIds(type).contains(TerminalType.partId(part))) {
			return Optional.of(TerminalRefusal.ALREADY_INSERTED);
		}
		return Optional.empty();
	}

	/** Puts {@code part} into {@code type}, or returns why it cannot go in (see {@link #check}). A refusal changes nothing. */
	public Optional<TerminalRefusal> insert(TerminalType type, Item part) {
		Optional<TerminalRefusal> refusal = check(type, part);
		if (refusal.isEmpty()) {
			readable().computeIfAbsent(type.id(), id -> new ArrayList<>()).add(TerminalType.partId(part));
			setDirty();
		}
		return refusal;
	}
}
