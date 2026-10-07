package io.github.pkeppeler.deepcharter.transmission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
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
 * What each charter has been told: the transmissions it has fired, and the ordered queue of those not yet delivered, kept in a
 * {@code SavedData} of its own, because the charter code takes no change from this feature. Plus the world's replay list: the
 * repair transmissions fired live by any charter, in the order they first fired, for charters founded later.
 *
 * <p>This holds the state and its rules only. {@link Transmissions} adds delivery and bonuses on top of it. Call on the server thread.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and every use of it
 * throws, as {@code CharterData} does ({@code docs/adr/0007}).
 */
public final class TransmissionData extends SavedData {
	public static final int VERSION = 1;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "transmissions");

	/** The fired set and the queue of one charter, both in the order the transmissions fired. The queue is a part of the fired set. */
	public record Progress(List<Identifier> fired, List<Identifier> queue) {
		public static final Progress EMPTY = new Progress(List.of(), List.of());

		public Progress {
			fired = List.copyOf(fired);
			queue = List.copyOf(queue);
			if (new LinkedHashSet<>(fired).size() != fired.size()) {
				throw new IllegalArgumentException("a transmission is fired once: " + fired);
			}
			if (!fired.containsAll(queue)) {
				throw new IllegalArgumentException("the queue " + queue + " holds a transmission that has not fired: " + fired);
			}
		}

		Progress with(Identifier id) {
			List<Identifier> newFired = new ArrayList<>(fired);
			newFired.add(id);
			List<Identifier> newQueue = new ArrayList<>(queue);
			newQueue.add(id);
			return new Progress(newFired, newQueue);
		}
	}

	private record Entry(CharterId charter, Progress progress) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.<Fields>create(instance -> instance.group(
				CharterId.CODEC.fieldOf("charter").forGetter(Fields::charter),
				Identifier.CODEC.listOf().fieldOf("fired").forGetter(Fields::fired),
				Identifier.CODEC.listOf().fieldOf("queue").forGetter(Fields::queue)).apply(instance, Fields::new))
				.flatXmap(Fields::toEntry, entry -> DataResult.success(new Fields(entry.charter, entry.progress.fired(), entry.progress.queue())));
	}

	private record Fields(CharterId charter, List<Identifier> fired, List<Identifier> queue) {
		DataResult<Entry> toEntry() {
			try {
				return DataResult.success(new Entry(charter, new Progress(fired, queue)));
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

	private final Map<CharterId, Progress> charters = new LinkedHashMap<>();
	private final List<Identifier> replays = new ArrayList<>();
	private final Optional<Versioned.Unreadable<Body>> unreadable;

	public TransmissionData() {
		this.unreadable = Optional.empty();
	}

	private TransmissionData(Versioned<Body> loaded) {
		switch (loaded) {
			case Versioned.Readable<Body> readable -> {
				readable.value().charters().forEach(entry -> charters.put(entry.charter(), entry.progress()));
				replays.addAll(readable.value().replays());
				unreadable = Optional.empty();
			}
			case Versioned.Unreadable<Body> raw -> unreadable = Optional.of(raw);
		}
	}

	/** The world's transmission state. Call on the server thread. */
	public static TransmissionData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	private Versioned<Body> versioned() {
		return unreadable.<Versioned<Body>>map(raw -> raw).orElseGet(() -> Versioned.of(new Body(
				charters.entrySet().stream().map(entry -> new Entry(entry.getKey(), entry.getValue())).toList(), List.copyOf(replays))));
	}

	private void requireReadable() {
		if (unreadable.isPresent()) {
			throw new IllegalStateException("the saved transmissions have version " + unreadable.get().version()
					+ " that this build cannot read (it reads " + VERSION + ")");
		}
	}

	/** What {@code charter} has fired and not yet been sent. A charter that has fired nothing has none of either. */
	public Progress progress(CharterId charter) {
		requireReadable();
		return charters.getOrDefault(charter, Progress.EMPTY);
	}

	/** The repair transmissions fired live so far, in the order they first fired. */
	public List<Identifier> replays() {
		requireReadable();
		return List.copyOf(replays);
	}

	/**
	 * Fires {@code transmission} for {@code charter}: it joins the fired set and the end of the queue. Returns false, and changes
	 * nothing, when the charter has it already. A repair transmission also joins the world's replay list. This is the one place that
	 * decides "once", so a bonus is credited exactly when this returns true.
	 */
	public boolean fire(CharterId charter, Transmission transmission) {
		if (!add(charter, transmission.id())) {
			return false;
		}
		if (transmission.replay() && !replays.contains(transmission.id())) {
			replays.add(transmission.id());
		}
		return true;
	}

	/**
	 * Adds every transmission of the world's replay list that {@code charter} does not have to its fired set and its queue, in the
	 * order they first fired, and returns them. Called for a charter that has just been founded.
	 */
	public List<Identifier> replayTo(CharterId charter) {
		requireReadable();
		List<Identifier> added = new ArrayList<>();
		for (Identifier id : replays) {
			if (add(charter, id)) {
				added.add(id);
			}
		}
		return added;
	}

	/** Empties the queue of {@code charter} and returns what it held, in order. The fired set keeps them. */
	public List<Identifier> takeQueue(CharterId charter) {
		Progress progress = progress(charter);
		if (progress.queue().isEmpty()) {
			return List.of();
		}
		charters.put(charter, new Progress(progress.fired(), List.of()));
		setDirty();
		return progress.queue();
	}

	private boolean add(CharterId charter, Identifier id) {
		Progress progress = progress(charter);
		if (progress.fired().contains(id)) {
			return false;
		}
		charters.put(charter, progress.with(id));
		setDirty();
		return true;
	}
}
