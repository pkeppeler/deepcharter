package io.github.pkeppeler.deepcharter.hangar;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.SavedState;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;

/**
 * What the hangar remembers, saved with the world: the founding Mole (the derelict placed once when the colony was built),
 * the charter that repaired it, and the pods each charter got from the hangar.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable and is written back unchanged, and
 * every use of it throws, as for {@code ColonySite}. Callbacks go through {@link #readable}, which never throws.
 */
public final class HangarData extends SavedData {
	public static final int VERSION = 1;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hangar");

	/** The pods of one charter that the hangar knows. */
	public record Held(CharterId charter, List<UUID> pods) {
		private static final Codec<Held> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				CharterId.CODEC.fieldOf("charter").forGetter(Held::charter),
				UUIDUtil.CODEC.listOf().fieldOf("pods").forGetter(Held::pods)).apply(instance, Held::new));

		public Held {
			pods = List.copyOf(pods);
		}
	}

	/**
	 * @param derelict the founding Mole's pod, once placed: the hangar places it once, so a world that has one never gets a second
	 * @param founder  the charter whose last part repaired the founding Mole
	 * @param founded  the founding Mole belongs to {@code founder}: it is registered and restored
	 * @param held     the pods the hangar gave each charter. A pod a charter owns by another road counts when it is loaded.
	 */
	public record State(Optional<UUID> derelict, Optional<CharterId> founder, boolean founded, List<Held> held) {
		private static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				UUIDUtil.CODEC.optionalFieldOf("derelict").forGetter(State::derelict),
				CharterId.CODEC.optionalFieldOf("founder").forGetter(State::founder),
				Codec.BOOL.fieldOf("founded").forGetter(State::founded),
				Held.CODEC.listOf().fieldOf("held").forGetter(State::held)).apply(instance, State::new));
		static final State EMPTY = new State(Optional.empty(), Optional.empty(), false, List.of());

		public State {
			held = List.copyOf(held);
		}
	}

	private static final Codec<Versioned<State>> VERSIONED_CODEC = Versioned.codec(VERSION, State.BODY);
	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<HangarData> CODEC = VERSIONED_CODEC.xmap(HangarData::new, HangarData::versioned);
	// Datafixer type: as for CharterData (ADR 0007), vanilla's fixers find nothing of theirs in a file that carries our own version.
	public static final SavedDataType<HangarData> TYPE = new SavedDataType<>(ID, HangarData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final SavedState<State> saved;

	public HangarData() {
		this.saved = SavedState.fresh(ID, VERSION, State.EMPTY);
	}

	private HangarData(Versioned<State> loaded) {
		this.saved = SavedState.load(ID, VERSION, loaded, state -> state);
	}

	/** The world's hangar data. Call on the server thread. */
	public static HangarData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	/** The world's hangar data if it is readable; otherwise empty, after logging that once per data. Never throws. */
	public static Optional<HangarData> readable(MinecraftServer server) {
		return Optional.of(get(server)).filter(HangarData::isReadable);
	}

	private Versioned<State> versioned() {
		return saved.versioned(state -> state);
	}

	/** False (logged once) when the saved data is of a version this build cannot read: every other method then throws. */
	public boolean isReadable() {
		return saved.isReadable();
	}

	public State state() {
		return saved.orThrow();
	}

	private void change(UnaryOperator<State> change) {
		saved.set(change.apply(state()));
		setDirty();
	}

	/** Records the founding Mole. It is placed once: a second call throws. */
	void placeDerelict(UUID pod) {
		if (state().derelict().isPresent()) {
			throw new IllegalStateException("the founding Mole is placed already");
		}
		change(old -> new State(Optional.of(pod), old.founder(), old.founded(), old.held()));
	}

	/** Records the charter that repaired the founding Mole. It is the first one: a second call throws. */
	void setFounder(CharterId charter) {
		if (state().founder().isPresent()) {
			throw new IllegalStateException("the founding Mole has a founder already");
		}
		change(old -> new State(old.derelict(), Optional.of(charter), old.founded(), old.held()));
	}

	/** Records that the founding Mole is registered to its founder and works. */
	void markFounded() {
		change(old -> new State(old.derelict(), old.founder(), true, old.held()));
	}

	/** Records a pod as the charter's. Recording it twice changes nothing. */
	void hold(CharterId charter, UUID pod) {
		change(old -> {
			List<Held> held = new ArrayList<>(old.held());
			Set<UUID> pods = new LinkedHashSet<>(held(old, charter));
			pods.add(pod);
			held.removeIf(entry -> entry.charter().equals(charter));
			held.add(new Held(charter, List.copyOf(pods)));
			return new State(old.derelict(), old.founder(), old.founded(), held);
		});
	}

	/** The pods recorded as the charter's. */
	public Set<UUID> held(CharterId charter) {
		return held(state(), charter);
	}

	private static Set<UUID> held(State from, CharterId charter) {
		return from.held().stream().filter(entry -> entry.charter().equals(charter)).flatMap(entry -> entry.pods().stream())
				.collect(Collectors.toUnmodifiableSet());
	}
}
