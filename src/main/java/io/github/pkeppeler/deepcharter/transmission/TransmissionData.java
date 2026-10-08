package io.github.pkeppeler.deepcharter.transmission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
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
 * What each charter has been told, in a {@code SavedData} of its own, because the charter code takes no change from this feature.
 *
 * <ul>
 *   <li>Per charter: the transmissions it has fired, in order, and for each member how many of them that member has been sent (a
 *       cursor into the fired list). A member with no cursor has been sent none, so a member who joins later is sent the story so far.
 *   <li>Per charter: the bonuses that could not be credited yet because the account was full, to try again.
 *   <li>For the world: the repair transmissions fired live by any charter, in the order they first fired, for charters founded later.
 * </ul>
 *
 * <p>This holds the state and its rules only. {@link Transmissions} adds delivery and bonuses on top of it. Call on the server thread.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and
 * {@link #isReadable()} is false: {@link Transmissions} then does nothing and logs once, so a newer world is never overwritten and no
 * tick or login fails. Every method here throws on unreadable data ({@code docs/adr/0007}).
 */
public final class TransmissionData extends SavedData {
	public static final int VERSION = 1;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "transmissions");

	/**
	 * What one charter has fired, how far each member has been sent it, and which bonuses wait to be credited.
	 *
	 * @param fired   every transmission the charter has fired, in order, each once
	 * @param cursors per member, how many of {@code fired} the member has been sent, from 0 to the size of {@code fired}
	 * @param pending bonuses fired but not yet credited, in order
	 */
	public record Progress(List<Identifier> fired, Map<UUID, Integer> cursors, List<Transmission.Bonus> pending) {
		public static final Progress EMPTY = new Progress(List.of(), Map.of(), List.of());

		public Progress {
			fired = List.copyOf(fired);
			cursors = Map.copyOf(cursors);
			pending = List.copyOf(pending);
			if (new LinkedHashSet<>(fired).size() != fired.size()) {
				throw new IllegalArgumentException("a transmission is fired once: " + fired);
			}
			int firedCount = fired.size();
			cursors.forEach((member, sent) -> {
				if (sent < 0 || sent > firedCount) {
					throw new IllegalArgumentException("member " + member + " cannot have been sent " + sent + " of " + firedCount);
				}
			});
		}

		/** The transmissions {@code member} has not been sent yet, in order. */
		public List<Identifier> unsent(UUID member) {
			return fired.subList(cursors.getOrDefault(member, 0), fired.size());
		}

		private Progress withFired(Identifier id) {
			List<Identifier> newFired = new ArrayList<>(fired);
			newFired.add(id);
			return new Progress(newFired, cursors, pending);
		}

		private Progress withSent(UUID member, int sent) {
			Map<UUID, Integer> newCursors = new LinkedHashMap<>(cursors);
			newCursors.put(member, sent);
			return new Progress(fired, newCursors, pending);
		}

		private Progress without(UUID member) {
			Map<UUID, Integer> newCursors = new LinkedHashMap<>(cursors);
			newCursors.remove(member);
			return new Progress(fired, newCursors, pending);
		}

		private Progress withPending(List<Transmission.Bonus> newPending) {
			return new Progress(fired, cursors, newPending);
		}
	}

	private record Cursor(UUID member, int sent) {
		static final Codec<Cursor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				UUIDUtil.CODEC.fieldOf("member").forGetter(Cursor::member),
				Codec.INT.fieldOf("sent").forGetter(Cursor::sent)).apply(instance, Cursor::new));
	}

	private record Entry(CharterId charter, Progress progress) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.<Fields>create(instance -> instance.group(
				CharterId.CODEC.fieldOf("charter").forGetter(Fields::charter),
				Identifier.CODEC.listOf().fieldOf("fired").forGetter(Fields::fired),
				Cursor.CODEC.listOf().fieldOf("cursors").forGetter(Fields::cursors),
				Transmission.Bonus.CODEC.listOf().fieldOf("pending_bonuses").forGetter(Fields::pending)).apply(instance, Fields::new))
				.flatXmap(Fields::toEntry, entry -> DataResult.success(Fields.of(entry)));
	}

	private record Fields(CharterId charter, List<Identifier> fired, List<Cursor> cursors, List<Transmission.Bonus> pending) {
		static Fields of(Entry entry) {
			Progress progress = entry.progress();
			return new Fields(entry.charter(), progress.fired(),
					progress.cursors().entrySet().stream().map(cursor -> new Cursor(cursor.getKey(), cursor.getValue())).toList(), progress.pending());
		}

		DataResult<Entry> toEntry() {
			try {
				Map<UUID, Integer> byMember = new LinkedHashMap<>();
				for (Cursor cursor : cursors) {
					if (byMember.put(cursor.member(), cursor.sent()) != null) {
						return DataResult.error(() -> "transmissions of charter " + charter.value() + ": member " + cursor.member() + " has two cursors");
					}
				}
				return DataResult.success(new Entry(charter, new Progress(fired, byMember, pending)));
			} catch (IllegalArgumentException e) {
				return DataResult.error(() -> "transmissions of charter " + charter.value() + ": " + e.getMessage());
			}
		}
	}

	private record Body(List<Entry> charters, List<Identifier> replays) {
	}

	private static final MapCodec<Body> BODY_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Entry.CODEC.listOf().fieldOf("charters").forGetter(Body::charters),
			Identifier.CODEC.listOf().fieldOf("replays").forGetter(Body::replays)).apply(instance, Body::new));
	private static final Codec<Versioned<Body>> VERSIONED_CODEC = Versioned.codec(VERSION, BODY_CODEC);

	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<TransmissionData> CODEC = VERSIONED_CODEC.xmap(TransmissionData::new, TransmissionData::versioned);
	// Datafixer type: vanilla applies it to saved data it reads. Our data has a version of its own, so the vanilla fixers find nothing to fix.
	public static final SavedDataType<TransmissionData> TYPE = new SavedDataType<>(ID, TransmissionData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	/** What the class works on: each charter's progress, and the world's replay list. */
	private record Live(Map<CharterId, Progress> charters, List<Identifier> replays) {
	}

	private final SavedState<Live> state;

	public TransmissionData() {
		this.state = SavedState.fresh(ID, VERSION, new Live(new LinkedHashMap<>(), new ArrayList<>()));
	}

	private TransmissionData(Versioned<Body> loaded) {
		this.state = SavedState.load(ID, VERSION, loaded, body -> {
			Live live = new Live(new LinkedHashMap<>(), new ArrayList<>(body.replays()));
			body.charters().forEach(entry -> live.charters().put(entry.charter(), entry.progress()));
			return live;
		});
	}

	/** The world's transmission state. Call on the server thread. */
	public static TransmissionData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	private Versioned<Body> versioned() {
		return state.versioned(live -> new Body(
				live.charters().entrySet().stream().map(entry -> new Entry(entry.getKey(), entry.getValue())).toList(), List.copyOf(live.replays())));
	}

	/** False (logged once) when the saved data is of a version this build cannot read. Never throws: check it before using the data from a tick or an event. */
	public boolean isReadable() {
		return state.isReadable();
	}

	private Live live() {
		return state.orThrow();
	}

	/** What {@code charter} has fired and been sent. A charter that has fired nothing has none of either. */
	public Progress progress(CharterId charter) {
		return live().charters().getOrDefault(charter, Progress.EMPTY);
	}

	/** The repair transmissions fired live so far, in the order they first fired. */
	public List<Identifier> replays() {
		return List.copyOf(live().replays());
	}

	/**
	 * Fires {@code transmission} for {@code charter}: it joins the end of the fired set. Returns false, and changes nothing, when the
	 * charter has it already. A repair transmission also joins the world's replay list. This is the one place that decides "once", so a
	 * bonus is credited exactly when this returns true.
	 */
	public boolean fire(CharterId charter, Transmission transmission) {
		if (!add(charter, transmission.id())) {
			return false;
		}
		if (transmission.replay() && !live().replays().contains(transmission.id())) {
			live().replays().add(transmission.id());
		}
		return true;
	}

	/**
	 * Adds every transmission of the world's replay list that {@code charter} does not have to its fired set, in the order they first
	 * fired, and returns them. Called for a charter that has just been founded.
	 */
	public List<Identifier> replayTo(CharterId charter) {
		List<Identifier> added = new ArrayList<>();
		for (Identifier id : live().replays()) {
			if (add(charter, id)) {
				added.add(id);
			}
		}
		return added;
	}

	/** Records that {@code member} has been sent the first {@code sent} transmissions of {@code charter}. It never goes back. */
	public void markSent(CharterId charter, UUID member, int sent) {
		Progress progress = progress(charter);
		if (sent > progress.fired().size()) {
			throw new IllegalArgumentException("cannot have sent " + sent + " of " + progress.fired().size());
		}
		if (sent > progress.cursors().getOrDefault(member, 0)) {
			live().charters().put(charter, progress.withSent(member, sent));
			setDirty();
		}
	}

	/** Forgets how much {@code member} has been sent: a member who leaves and returns is sent the story from the start. */
	public void forget(CharterId charter, UUID member) {
		Progress progress = progress(charter);
		if (progress.cursors().containsKey(member)) {
			live().charters().put(charter, progress.without(member));
			setDirty();
		}
	}

	/** Adds a bonus that could not be credited, to try again. */
	public void addPending(CharterId charter, Transmission.Bonus bonus) {
		Progress progress = progress(charter);
		List<Transmission.Bonus> pending = new ArrayList<>(progress.pending());
		pending.add(bonus);
		live().charters().put(charter, progress.withPending(pending));
		setDirty();
	}

	/** Removes the first pending bonus, which has now been credited. */
	public void removeFirstPending(CharterId charter) {
		Progress progress = progress(charter);
		List<Transmission.Bonus> pending = new ArrayList<>(progress.pending());
		if (pending.isEmpty()) {
			throw new IllegalStateException("charter " + charter.value() + " has no pending bonus");
		}
		pending.removeFirst();
		live().charters().put(charter, progress.withPending(pending));
		setDirty();
	}

	private boolean add(CharterId charter, Identifier id) {
		Progress progress = progress(charter);
		if (progress.fired().contains(id)) {
			return false;
		}
		live().charters().put(charter, progress.withFired(id));
		setDirty();
		return true;
	}
}
