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

	/** The catalysts of the Company's advance that one charter has used. */
	public record Advanced(CharterId charter, int spent) {
		private static final Codec<Advanced> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				CharterId.CODEC.fieldOf("charter").forGetter(Advanced::charter),
				Codec.INT.fieldOf("spent").forGetter(Advanced::spent)).apply(instance, Advanced::new));
	}

	/**
	 * @param derelict the founding Mole's pod, once placed: the hangar places it once, so a world that has one never gets a second
	 * @param founder  the charter whose last part repaired the founding Mole
	 * @param founded  the founding Mole belongs to {@code founder}: it is registered and restored
	 * @param held     the pods the hangar gave each charter. A pod a charter owns by another road counts when it is loaded.
	 * @param advanced the catalysts of the Company's advance that each charter has used. Absent from data saved before the advance
	 *                 (it reads as nothing used), so the version stays 1.
	 */
	public record State(Optional<UUID> derelict, Optional<CharterId> founder, boolean founded, List<Held> held, List<Advanced> advanced) {
		private static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				UUIDUtil.CODEC.optionalFieldOf("derelict").forGetter(State::derelict),
				CharterId.CODEC.optionalFieldOf("founder").forGetter(State::founder),
				Codec.BOOL.fieldOf("founded").forGetter(State::founded),
				Held.CODEC.listOf().fieldOf("held").forGetter(State::held),
				Advanced.CODEC.listOf().optionalFieldOf("advanced", List.of()).forGetter(State::advanced)).apply(instance, State::new));
		static final State EMPTY = new State(Optional.empty(), Optional.empty(), false, List.of(), List.of());

		public State {
			held = List.copyOf(held);
			advanced = List.copyOf(advanced);
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
		change(old -> new State(Optional.of(pod), old.founder(), old.founded(), old.held(), old.advanced()));
	}

	/** Records the charter that repaired the founding Mole. It is the first one: a second call throws. */
	void setFounder(CharterId charter) {
		if (state().founder().isPresent()) {
			throw new IllegalStateException("the founding Mole has a founder already");
		}
		change(old -> new State(old.derelict(), Optional.of(charter), old.founded(), old.held(), old.advanced()));
	}

	/** Records that the founding Mole is registered to its founder and works. */
	void markFounded() {
		change(old -> new State(old.derelict(), old.founder(), true, old.held(), old.advanced()));
	}

	/** Records a pod as the charter's. Recording it twice changes nothing. */
	void hold(CharterId charter, UUID pod) {
		change(old -> {
			List<Held> held = new ArrayList<>(old.held());
			Set<UUID> pods = new LinkedHashSet<>(held(old, charter));
			pods.add(pod);
			held.removeIf(entry -> entry.charter().equals(charter));
			held.add(new Held(charter, List.copyOf(pods)));
			return new State(old.derelict(), old.founder(), old.founded(), held, old.advanced());
		});
	}

	/** The catalysts of the Company's advance that the charter has used so far. */
	public int advanceSpent(CharterId charter) {
		return state().advanced().stream().filter(entry -> entry.charter().equals(charter)).mapToInt(Advanced::spent).sum();
	}

	/** The catalysts of an advance of {@code advance} that the charter has not used: never below 0. */
	public int advanceLeft(CharterId charter, int advance) {
		return Math.max(0, advance - advanceSpent(charter));
	}

	/** Records that the charter used {@code catalysts} more of the Company's advance. */
	public void spendAdvance(CharterId charter, int catalysts) {
		if (catalysts < 0) {
			throw new IllegalArgumentException("cannot spend " + catalysts + " catalysts of an advance");
		}
		change(old -> {
			List<Advanced> advanced = new ArrayList<>(old.advanced());
			int spent = advanceSpent(charter) + catalysts;
			advanced.removeIf(entry -> entry.charter().equals(charter));
			advanced.add(new Advanced(charter, spent));
			return new State(old.derelict(), old.founder(), old.founded(), old.held(), advanced);
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
