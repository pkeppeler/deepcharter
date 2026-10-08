package io.github.pkeppeler.deepcharter.market;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import io.github.pkeppeler.deepcharter.attachment.SavedState;
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

	private final SavedState<Map<Key, Integer>> state;

	public WorkOrderData() {
		this.state = SavedState.fresh(ID, VERSION, new HashMap<>());
	}

	private WorkOrderData(Versioned<List<Progress>> loaded) {
		this.state = SavedState.load(ID, VERSION, loaded, list -> {
			Map<Key, Integer> delivered = new HashMap<>();
			list.forEach(progress -> delivered.put(new Key(progress.charter(), progress.order()), progress.delivered()));
			return delivered;
		});
	}

	/** The world's work order progress. Call on the server thread. */
	public static WorkOrderData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	private Versioned<List<Progress>> versioned() {
		return state.versioned(delivered -> delivered.entrySet().stream()
				.map(entry -> new Progress(entry.getKey().charter(), entry.getKey().order(), entry.getValue())).toList());
	}

	/** False (logged once) when the saved data is of a version this build cannot read: every other method then throws. */
	public boolean isReadable() {
		return state.isReadable();
	}

	/** How many the charter has handed in for {@code order}. */
	public int delivered(CharterId charter, WorkOrder order) {
		return state.orThrow().getOrDefault(new Key(charter, order), 0);
	}

	/** Records {@code amount} more handed in. It must be at least 1 and must not take the order past its quantity. */
	void add(CharterId charter, WorkOrder order, int amount) {
		int total = delivered(charter, order) + amount;
		if (amount < 1 || total > order.quantity()) {
			throw new IllegalArgumentException("cannot hand in " + amount + " more for " + order + " with " + delivered(charter, order) + " in");
		}
		state.orThrow().put(new Key(charter, order), total);
		setDirty();
	}
}
