package io.github.pkeppeler.deepcharter.pod;

import java.util.Map;
import java.util.TreeMap;

import com.mojang.serialization.Codec;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.SavedState;
import io.github.pkeppeler.deepcharter.attachment.Versioned;

/**
 * The serial numbers of pods and parts, saved with the world: one counter for each prefix, so the pods are {@code MOLE-0001},
 * {@code MOLE-0002} and the drills are {@code DRILL-0001}. A serial is never reused, and {@link #FAMOUS_PROSPECTOR} is never issued.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and
 * handing out a serial then throws, so a newer world is never overwritten by an older build.
 */
public final class Serials extends SavedData {
	public static final int VERSION = 1;
	/**
	 * The number of the wreck that lies at the site nearest the Conduit. It is the lore wreck's from the day the world is made, so
	 * {@link #next} never hands it out, and a restore of that wreck registers it under this serial (ADR 0027).
	 */
	public static final String FAMOUS_PROSPECTOR = "PROSPECTOR-0002";
	private static final String FAMOUS_CLAIM = "claimed:" + FAMOUS_PROSPECTOR;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "serials");
	private static final Codec<Versioned<Map<String, Integer>>> VERSIONED_CODEC =
			Versioned.codec(VERSION, Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("issued"));

	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<Serials> CODEC = VERSIONED_CODEC.xmap(Serials::new, Serials::versioned);
	// Datafixer type: vanilla applies it to saved data it reads. Ours has a version of its own, so the vanilla fixers find nothing to fix.
	public static final SavedDataType<Serials> TYPE = new SavedDataType<>(ID, Serials::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	/** How many serials of each prefix have been handed out. */
	private final SavedState<Map<String, Integer>> state;

	public Serials() {
		this.state = SavedState.fresh(ID, VERSION, new TreeMap<>());
	}

	private Serials(Versioned<Map<String, Integer>> loaded) {
		this.state = SavedState.load(ID, VERSION, loaded, TreeMap::new);
	}

	/** The world's serials. Call on the server thread. */
	public static Serials get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	/** False (logged once) when the saved serials are of a version this build cannot read, so {@link #next} throws. Check it on a gameplay path. */
	public boolean isReadable() {
		return state.isReadable();
	}

	/** The next serial of {@code prefix}, such as {@code MOLE-0001}. Throws if the saved serials are unreadable. */
	public String next(String prefix) {
		Map<String, Integer> issued = state.orThrow();
		if (prefix.isBlank() || prefix.contains(":")) {
			throw new IllegalArgumentException("a serial needs a prefix with no colon, got '" + prefix + "'");
		}
		int number = issued.getOrDefault(prefix, 0) + 1;
		if (format(prefix, number).equals(FAMOUS_PROSPECTOR)) {
			number++;
		}
		issued.put(prefix, number);
		setDirty();
		return format(prefix, number);
	}

	/**
	 * Records that a charter registered the lore wreck under {@link #FAMOUS_PROSPECTOR}: the number is held once in the world, so a
	 * second claim throws. The claim is a count under a key no prefix can have (a prefix has no colon), so the saved shape is still
	 * version 1. Throws if the saved serials are unreadable.
	 */
	public void claimFamousProspector() {
		Map<String, Integer> issued = state.orThrow();
		if (issued.putIfAbsent(FAMOUS_CLAIM, 1) != null) {
			throw new IllegalStateException(FAMOUS_PROSPECTOR + " is already held by a pod");
		}
		setDirty();
	}

	/** True once a pod holds {@link #FAMOUS_PROSPECTOR}, see {@link #claimFamousProspector}. Throws if the saved serials are unreadable. */
	public boolean isFamousProspectorHeld() {
		return state.orThrow().containsKey(FAMOUS_CLAIM);
	}

	private static String format(String prefix, int number) {
		return String.format("%s-%04d", prefix, number);
	}

	private Versioned<Map<String, Integer>> versioned() {
		return state.versioned(Map::copyOf);
	}
}
