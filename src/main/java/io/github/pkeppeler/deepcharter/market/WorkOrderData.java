package io.github.pkeppeler.deepcharter.market;

import java.util.HashMap;
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
 * How much of each {@link WorkOrder} each charter has handed in. A charter with no entry has handed in none; an order is done
 * when the amount reaches {@link WorkOrder#quantity()}.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and every
 * use of it throws, as for {@code ColonySite}: callers that run on a callback check {@link #isReadable()} first.
 */
public final class WorkOrderData extends SavedData {
	public static final int VERSION = 1;
	/** The saved id of the data: never renamed, or every charter's progress is orphaned. */
	public static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "work_orders");

	private record Progress(CharterId charter, WorkOrder order, int delivered) {
		private static final Codec<Progress> CODEC = RecordCodecBuilder.<Progress>create(instance -> instance.group(
				CharterId.CODEC.fieldOf("charter").forGetter(Progress::charter),
				WorkOrder.CODEC.fieldOf("order").forGetter(Progress::order),
				Codec.INT.fieldOf("delivered").forGetter(Progress::delivered)).apply(instance, Progress::new))
				.validate(progress -> progress.delivered() >= 1 && progress.delivered() <= progress.order().quantity()
						? DataResult.success(progress)
						: DataResult.error(() -> "work order " + progress.order() + " is saved with " + progress.delivered() + " delivered"));
	}

	private record Key(CharterId charter, WorkOrder order) {
	}

	private static final MapCodec<List<Progress>> BODY = Progress.CODEC.listOf().fieldOf("progress");
	private static final Codec<Versioned<List<Progress>>> VERSIONED_CODEC = Versioned.codec(VERSION, BODY);

	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<WorkOrderData> CODEC = VERSIONED_CODEC.xmap(WorkOrderData::new, WorkOrderData::versioned);
	// Datafixer type: as for ColonySite, vanilla's fixers find nothing of theirs in a file that carries our own version.
	public static final SavedDataType<WorkOrderData> TYPE = new SavedDataType<>(ID, WorkOrderData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<Key, Integer> delivered = new HashMap<>();
	private final Optional<Versioned.Unreadable<List<Progress>>> unreadable;
	private boolean loggedUnreadable;

	public WorkOrderData() {
		this.unreadable = Optional.empty();
	}

	private WorkOrderData(Versioned<List<Progress>> loaded) {
		switch (loaded) {
			case Versioned.Readable<List<Progress>> readable -> {
				readable.value().forEach(progress -> delivered.put(new Key(progress.charter(), progress.order()), progress.delivered()));
				unreadable = Optional.empty();
			}
			case Versioned.Unreadable<List<Progress>> raw -> unreadable = Optional.of(raw);
		}
	}

	/** The world's work order progress. Call on the server thread. */
	public static WorkOrderData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	private Versioned<List<Progress>> versioned() {
		return unreadable.<Versioned<List<Progress>>>map(raw -> raw).orElseGet(() -> Versioned.of(delivered.entrySet().stream()
				.map(entry -> new Progress(entry.getKey().charter(), entry.getKey().order(), entry.getValue())).toList()));
	}

	/** False when the saved data is of a version this build cannot read: every other method then throws. */
	public boolean isReadable() {
		return unreadable.isEmpty();
	}

	/** The saved version of unreadable data, for a log line. */
	public Optional<String> unreadableVersion() {
		return unreadable.map(Versioned.Unreadable::version);
	}

	/** True the first time it is asked, so a callback logs unreadable data once and then skips. */
	boolean firstUnreadableReport() {
		boolean first = !loggedUnreadable;
		loggedUnreadable = true;
		return first;
	}

	private void requireReadable() {
		if (unreadable.isPresent()) {
			throw new IllegalStateException("the saved work orders have version " + unreadable.get().version()
					+ " that this build cannot read (it reads " + VERSION + ")");
		}
	}

	/** How many the charter has handed in for {@code order}. */
	public int delivered(CharterId charter, WorkOrder order) {
		requireReadable();
		return delivered.getOrDefault(new Key(charter, order), 0);
	}

	/** True once the charter has handed in the whole of {@code order}. */
	public boolean done(CharterId charter, WorkOrder order) {
		return delivered(charter, order) >= order.quantity();
	}

	/** Records {@code amount} more handed in. It must be at least 1 and must not take the order past its quantity. */
	void add(CharterId charter, WorkOrder order, int amount) {
		int total = delivered(charter, order) + amount;
		if (amount < 1 || total > order.quantity()) {
			throw new IllegalArgumentException("cannot hand in " + amount + " more for " + order + " with " + delivered(charter, order) + " in");
		}
		delivered.put(new Key(charter, order), total);
		setDirty();
	}
}
