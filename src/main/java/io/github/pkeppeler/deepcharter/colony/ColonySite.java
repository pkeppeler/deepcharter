package io.github.pkeppeler.deepcharter.colony;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;

/**
 * Where the colony is: set once, when the colony is built at world spawn, and never changed. Holds the {@link ColonyAnchor}s.
 * Gameplay code reads it through {@link Colony}, which never throws.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and every
 * use of it throws, as for {@code CharterData} (ADR 0007).
 */
public final class ColonySite extends SavedData {
	public static final int VERSION = 1;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "colony");

	/**
	 * A built colony.
	 *
	 * @param center  the centre of the pad: the world spawn at the time the colony was built, at ground level
	 * @param anchors every anchor, so a colony saved without one is unreadable and not half there
	 */
	public record Placed(BlockPos center, Map<ColonyAnchor, BlockPos> anchors) {
		private static final Codec<Placed> CODEC = RecordCodecBuilder.<Placed>create(instance -> instance.group(
				BlockPos.CODEC.fieldOf("center").forGetter(Placed::center),
				Codec.unboundedMap(ColonyAnchor.CODEC, BlockPos.CODEC).fieldOf("anchors").forGetter(Placed::anchors)).apply(instance, Placed::new))
				.validate(placed -> placed.anchors().keySet().containsAll(List.of(ColonyAnchor.values()))
						? DataResult.success(placed)
						: DataResult.error(() -> "the colony is saved without every anchor"));

		public Placed {
			EnumMap<ColonyAnchor, BlockPos> sorted = new EnumMap<>(ColonyAnchor.class);
			anchors.forEach((anchor, pos) -> sorted.put(anchor, pos.immutable()));
			center = center.immutable();
			anchors = Collections.unmodifiableMap(sorted);
		}

		/** The Y of the pad's ground, which is the Y of the centre. */
		public int groundY() {
			return center.getY();
		}
	}

	private static final Codec<Versioned<Optional<Placed>>> VERSIONED_CODEC = Versioned.codec(VERSION, Placed.CODEC.optionalFieldOf("colony"));

	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<ColonySite> CODEC = VERSIONED_CODEC.xmap(ColonySite::new, ColonySite::versioned);
	// Datafixer type: as for CharterData (ADR 0007), vanilla's fixers find nothing of theirs in a file that carries our own version.
	public static final SavedDataType<ColonySite> TYPE = new SavedDataType<>(ID, ColonySite::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private Optional<Placed> placed = Optional.empty();
	private final Optional<Versioned.Unreadable<Optional<Placed>>> unreadable;
	private boolean loggedUnreadable;

	public ColonySite() {
		this.unreadable = Optional.empty();
	}

	private ColonySite(Versioned<Optional<Placed>> loaded) {
		switch (loaded) {
			case Versioned.Readable<Optional<Placed>> readable -> {
				placed = readable.value();
				unreadable = Optional.empty();
			}
			case Versioned.Unreadable<Optional<Placed>> raw -> unreadable = Optional.of(raw);
		}
	}

	/** The world's colony site. Call on the server thread. */
	public static ColonySite get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	private Versioned<Optional<Placed>> versioned() {
		return unreadable.<Versioned<Optional<Placed>>>map(raw -> raw).orElseGet(() -> Versioned.of(placed));
	}

	/** False when the saved data is of a version this build cannot read: every other method then throws. */
	public boolean isReadable() {
		return unreadable.isEmpty();
	}

	/** The saved version of unreadable data, for a log line. */
	public Optional<String> unreadableVersion() {
		return unreadable.map(Versioned.Unreadable::version);
	}

	/** True the first time it is asked, so a callback logs unreadable data once per site and then skips. */
	boolean firstUnreadableReport() {
		boolean first = !loggedUnreadable;
		loggedUnreadable = true;
		return first;
	}

	private Optional<Placed> readable() {
		if (unreadable.isPresent()) {
			throw new IllegalStateException("the saved colony has version " + unreadable.get().version()
					+ " that this build cannot read (it reads " + VERSION + ")");
		}
		return placed;
	}

	/** True once the colony has been built. */
	public boolean isBuilt() {
		return readable().isPresent();
	}

	/** The built colony, or empty before it is built. */
	public Optional<Placed> placed() {
		return readable();
	}

	/** Records the colony. It is built once: a second call throws. */
	void place(Placed colony) {
		if (readable().isPresent()) {
			throw new IllegalStateException("the colony is already built");
		}
		placed = Optional.of(colony);
		setDirty();
	}
}
