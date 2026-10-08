package io.github.pkeppeler.deepcharter.pod;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.serialization.Codec;

import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;

/** Every light block a pod placed and has not taken away, so a sweep can tell a stale one from a builder's (ADR 0024). */
public final class PodLightLedger extends SavedData {
	public static final int VERSION = 1;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_light_ledger");
	private static final Codec<Versioned<List<GlobalPos>>> VERSIONED_CODEC =
			Versioned.codec(VERSION, GlobalPos.CODEC.listOf().fieldOf("placed"));

	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<PodLightLedger> CODEC = VERSIONED_CODEC.xmap(PodLightLedger::new, PodLightLedger::versioned);
	// Datafixer type: vanilla applies it to saved data it reads. Ours has a version of its own, so the vanilla fixers find nothing to fix.
	public static final SavedDataType<PodLightLedger> TYPE = new SavedDataType<>(ID, PodLightLedger::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Set<GlobalPos> placed = new HashSet<>();
	private final Optional<Versioned.Unreadable<List<GlobalPos>>> unreadable;

	public PodLightLedger() {
		this.unreadable = Optional.empty();
	}

	private PodLightLedger(Versioned<List<GlobalPos>> loaded) {
		switch (loaded) {
			case Versioned.Readable<List<GlobalPos>> readable -> {
				placed.addAll(readable.value());
				unreadable = Optional.empty();
			}
			case Versioned.Unreadable<List<GlobalPos>> raw -> unreadable = Optional.of(raw);
		}
	}

	/** The world's ledger. Call on the server thread. */
	public static PodLightLedger get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	/** False when the saved ledger is of a version this build cannot read, so {@link #record} and {@link #forget} throw. */
	public boolean isReadable() {
		return unreadable.isEmpty();
	}

	/** The saved version of an unreadable ledger, for the log; throws if the ledger is readable. */
	String unreadableVersion() {
		return unreadable.orElseThrow(() -> new IllegalStateException("the ledger is readable")).version();
	}

	/** Notes a light block that is about to be placed at {@code pos}. */
	public void record(GlobalPos pos) {
		requireReadable();
		if (placed.add(pos)) {
			setDirty();
		}
	}

	/** Forgets {@code pos}, once its block is gone or was never placed. */
	public void forget(GlobalPos pos) {
		requireReadable();
		if (placed.remove(pos)) {
			setDirty();
		}
	}

	/** A copy of the entries. */
	public Set<GlobalPos> entries() {
		return Set.copyOf(placed);
	}

	private void requireReadable() {
		if (unreadable.isPresent()) {
			throw new IllegalStateException("the saved pod light ledger has version " + unreadableVersion()
					+ " that this build cannot read (it reads " + VERSION + ")");
		}
	}

	private Versioned<List<GlobalPos>> versioned() {
		return unreadable.<Versioned<List<GlobalPos>>>map(raw -> raw).orElseGet(() -> Versioned.of(List.copyOf(placed)));
	}
}
