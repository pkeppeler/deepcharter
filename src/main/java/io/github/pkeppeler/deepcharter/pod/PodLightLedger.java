package io.github.pkeppeler.deepcharter.pod;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.serialization.Codec;

import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.SavedState;
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

	private final SavedState<Set<GlobalPos>> state;

	public PodLightLedger() {
		this.state = SavedState.fresh(ID, VERSION, new HashSet<>());
	}

	private PodLightLedger(Versioned<List<GlobalPos>> loaded) {
		this.state = SavedState.load(ID, VERSION, loaded, HashSet::new);
	}

	/** The world's ledger. Call on the server thread. */
	public static PodLightLedger get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	/** False (logged once) when the saved ledger is of a version this build cannot read, so {@link #record}, {@link #forget} and {@link #entries} throw. */
	public boolean isReadable() {
		return state.isReadable();
	}

	/** Notes a light block that is about to be placed at {@code pos}. */
	public void record(GlobalPos pos) {
		if (state.orThrow().add(pos)) {
			setDirty();
		}
	}

	/** Forgets {@code pos}, once its block is gone or was never placed. */
	public void forget(GlobalPos pos) {
		if (state.orThrow().remove(pos)) {
			setDirty();
		}
	}

	/** A copy of the entries. Throws if the saved ledger is unreadable. */
	public Set<GlobalPos> entries() {
		return Set.copyOf(state.orThrow());
	}

	private Versioned<List<GlobalPos>> versioned() {
		return state.versioned(List::copyOf);
	}
}
