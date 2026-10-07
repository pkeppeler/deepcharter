package io.github.pkeppeler.deepcharter.pod;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.mojang.serialization.Codec;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;

/**
 * The serial numbers of pods and parts, saved with the world: one counter for each prefix, so the pods are {@code MOLE-0001},
 * {@code MOLE-0002} and the drills are {@code DRILL-0001}. A serial is never reused.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and
 * handing out a serial then throws, so a newer world is never overwritten by an older build.
 */
public final class Serials extends SavedData {
	public static final int VERSION = 1;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "serials");
	private static final Codec<Versioned<Map<String, Integer>>> VERSIONED_CODEC =
			Versioned.codec(VERSION, Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("issued"));

	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<Serials> CODEC = VERSIONED_CODEC.xmap(Serials::new, Serials::versioned);
	// Datafixer type: vanilla applies it to saved data it reads. Ours has a version of its own, so the vanilla fixers find nothing to fix.
	public static final SavedDataType<Serials> TYPE = new SavedDataType<>(ID, Serials::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	/** How many serials of each prefix have been handed out. */
	private final Map<String, Integer> issued = new TreeMap<>();
	private final Optional<Versioned.Unreadable<Map<String, Integer>>> unreadable;

	public Serials() {
		this.unreadable = Optional.empty();
	}

	private Serials(Versioned<Map<String, Integer>> loaded) {
		switch (loaded) {
			case Versioned.Readable<Map<String, Integer>> readable -> {
				issued.putAll(readable.value());
				unreadable = Optional.empty();
			}
			case Versioned.Unreadable<Map<String, Integer>> raw -> unreadable = Optional.of(raw);
		}
	}

	/** The world's serials. Call on the server thread. */
	public static Serials get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	/** False when the saved serials are of a version this build cannot read, so {@link #next} throws. Check it on a gameplay path. */
	public boolean isReadable() {
		return unreadable.isEmpty();
	}

	/** The next serial of {@code prefix}, such as {@code MOLE-0001}. */
	public String next(String prefix) {
		if (unreadable.isPresent()) {
			throw new IllegalStateException("the saved serials have version " + unreadable.get().version()
					+ " that this build cannot read (it reads " + VERSION + ")");
		}
		if (prefix.isBlank()) {
			throw new IllegalArgumentException("a serial needs a prefix");
		}
		int number = issued.merge(prefix, 1, Integer::sum);
		setDirty();
		return String.format("%s-%04d", prefix, number);
	}

	private Versioned<Map<String, Integer>> versioned() {
		return unreadable.<Versioned<Map<String, Integer>>>map(raw -> raw).orElseGet(() -> Versioned.of(Map.copyOf(issued)));
	}
}
