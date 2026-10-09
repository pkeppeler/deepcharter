package io.github.pkeppeler.deepcharter.market;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.nbt.NbtOps;
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
 * How much of each {@link WorkOrder} each charter has handed in, and how many rounds of a repeatable one it has completed. A charter
 * with no entry has handed in none. A one-shot order is done when the amount reaches {@link WorkOrder#quantity()}; a repeatable order
 * starts its next round at that moment: the amount goes back to 0 and the rounds go up by one ({@link #add}).
 *
 * <p>The saved form has a {@link #VERSION}. Version 1 had no rounds (its one order was one-shot); it is still read, as rounds of 0,
 * and is written as version 2 from then on. Data of another version loads as unreadable, is written back unchanged, and every use of
 * it throws, as for {@code ColonySite}: callers that run on a callback check {@link #isReadable()} first.
 */
public final class WorkOrderData extends SavedData {
	/** 2 added the rounds of a repeatable order. */
	public static final int VERSION = 2;
	private static final int VERSION_WITHOUT_ROUNDS = 1;
	/** The saved id of the data: never renamed, or every charter's progress is orphaned. */
	public static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "work_orders");

	private record Progress(CharterId charter, WorkOrder order, int delivered, int rounds) {
		/** Version 2 saves the rounds; version 1 saved none, which reads as 0. Both are held to the order's rules. */
		private static Codec<Progress> codec(boolean savesRounds) {
			return RecordCodecBuilder.<Progress>create(instance -> instance.group(
					CharterId.CODEC.fieldOf("charter").forGetter(Progress::charter),
					WorkOrder.CODEC.fieldOf("order").forGetter(Progress::order),
					Codec.INT.fieldOf("delivered").forGetter(Progress::delivered),
					(savesRounds ? Codec.INT.fieldOf("rounds") : Codec.INT.optionalFieldOf("rounds", 0)).forGetter(Progress::rounds))
					.apply(instance, Progress::new)).validate(Progress::validated);
		}

		private DataResult<Progress> validated() {
			boolean fits = delivered >= 0 && rounds >= 0 && delivered <= order.quantity()
					&& (order.repeatable() ? delivered < order.quantity() : rounds == 0);
			return fits ? DataResult.success(this)
					: DataResult.error(() -> "work order " + order + " is saved with " + delivered + " delivered and " + rounds + " rounds");
		}
	}

	private record Key(CharterId charter, WorkOrder order) {
	}

	/** The amount handed in for the current round, and the rounds completed. */
	private record Count(int delivered, int rounds) {
		private static final Count NONE = new Count(0, 0);
	}

	private static final Codec<Versioned<List<Progress>>> CURRENT_CODEC = Versioned.codec(VERSION,
			Progress.codec(true).listOf().fieldOf("progress"));
	private static final Codec<Versioned<List<Progress>>> PREVIOUS_CODEC = Versioned.codec(VERSION_WITHOUT_ROUNDS,
			Progress.codec(false).listOf().fieldOf("progress"));

	/** Reads version 1 through its own codec, anything else through the current one; neither fails. Always writes the current one. */
	private static final Codec<Versioned<List<Progress>>> VERSIONED_CODEC = Codec.PASSTHROUGH.xmap(
			dynamic -> (dynamic.get(Versioned.VERSION_KEY).asNumber().result().map(Number::intValue).orElse(0) == VERSION_WITHOUT_ROUNDS
					? PREVIOUS_CODEC : CURRENT_CODEC).parse(dynamic).getOrThrow(),
			versioned -> new Dynamic<>(NbtOps.INSTANCE, CURRENT_CODEC.encodeStart(NbtOps.INSTANCE, versioned).getOrThrow()));

	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<WorkOrderData> CODEC = VERSIONED_CODEC.xmap(WorkOrderData::new, WorkOrderData::versioned);
	// Datafixer type: as for ColonySite, vanilla's fixers find nothing of theirs in a file that carries our own version.
	public static final SavedDataType<WorkOrderData> TYPE = new SavedDataType<>(ID, WorkOrderData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final SavedState<Map<Key, Count>> state;

	public WorkOrderData() {
		this.state = SavedState.fresh(ID, VERSION, new HashMap<>());
	}

	private WorkOrderData(Versioned<List<Progress>> loaded) {
		this.state = SavedState.load(ID, VERSION, loaded, list -> {
			Map<Key, Count> counts = new HashMap<>();
			list.forEach(progress -> counts.put(new Key(progress.charter(), progress.order()), new Count(progress.delivered(), progress.rounds())));
			return counts;
		});
	}

	/** The world's work order progress. Call on the server thread. */
	public static WorkOrderData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	private Versioned<List<Progress>> versioned() {
		return state.versioned(counts -> counts.entrySet().stream()
				.map(entry -> new Progress(entry.getKey().charter(), entry.getKey().order(), entry.getValue().delivered(), entry.getValue().rounds())).toList());
	}

	/** False (logged once) when the saved data is of a version this build cannot read: every other method then throws. */
	public boolean isReadable() {
		return state.isReadable();
	}

	private Count count(CharterId charter, WorkOrder order) {
		return state.orThrow().getOrDefault(new Key(charter, order), Count.NONE);
	}

	/** How many the charter has handed in for the current round of {@code order}. */
	public int delivered(CharterId charter, WorkOrder order) {
		return count(charter, order).delivered();
	}

	/** True when some charter has handed in all of a one-shot {@code order}: the world has done what the order does. */
	public boolean anyCompleted(WorkOrder order) {
		return state.orThrow().entrySet().stream().anyMatch(entry -> entry.getKey().order() == order && entry.getValue().delivered() >= order.quantity());
	}

	/** How many rounds of the repeatable {@code order} the charter has completed; always 0 for a one-shot order. */
	public int rounds(CharterId charter, WorkOrder order) {
		return count(charter, order).rounds();
	}

	/**
	 * Records {@code amount} more handed in. It must be at least 1 and must not take the round past its quantity. A repeatable order
	 * that reaches its quantity opens its next round: the amount returns to 0 and the rounds go up by one.
	 */
	void add(CharterId charter, WorkOrder order, int amount) {
		Count now = count(charter, order);
		int total = now.delivered() + amount;
		if (amount < 1 || total > order.quantity()) {
			throw new IllegalArgumentException("cannot hand in " + amount + " more for " + order + " with " + now.delivered() + " in");
		}
		boolean roundDone = total == order.quantity() && order.repeatable();
		state.orThrow().put(new Key(charter, order), roundDone ? new Count(0, now.rounds() + 1) : new Count(total, now.rounds()));
		setDirty();
	}
}
