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
import io.github.pkeppeler.deepcharter.attachment.SavedState;
import io.github.pkeppeler.deepcharter.attachment.Versioned;

/**
 * Where the colony is: set when its build begins at world spawn, marked finished when the build ends, and never moved. Holds the {@link ColonyAnchor}s.
 * Gameplay code reads it through {@link Colony}, which never throws.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and every
 * use of it throws, as for {@code CharterData} (ADR 0007). Version 1 had no {@code edgeGraded}: it loads with the edge counted as
 * graded, so a colony of an older world is never graded again, and is saved as version 2.
 */
public final class ColonySite extends SavedData {
	public static final int VERSION = 2;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "colony");

	/**
	 * A colony, begun or built.
	 *
	 * @param center   the centre of the pad at ground level: its Y is fixed when the build begins, so a build that stops half way
	 *                 is built again at the same height
	 * @param anchors  every anchor, so a colony saved without one is unreadable and not half there
	 * @param finished false while the build is under way
	 * @param edgeGraded true once the land around the pad is graded to it: the grade reads the natural ground, so it is done once
	 */
	public record Placed(BlockPos center, Map<ColonyAnchor, BlockPos> anchors, boolean finished, boolean edgeGraded) {
		private static final Codec<Placed> CODEC = RecordCodecBuilder.<Placed>create(instance -> instance.group(
				BlockPos.CODEC.fieldOf("center").forGetter(Placed::center),
				Codec.unboundedMap(ColonyAnchor.CODEC, BlockPos.CODEC).fieldOf("anchors").forGetter(Placed::anchors),
				Codec.BOOL.fieldOf("finished").forGetter(Placed::finished),
				Codec.BOOL.fieldOf("edgeGraded").forGetter(Placed::edgeGraded)).apply(instance, Placed::new))
				.validate(Placed::everyAnchor);

		/** The version 1 form, which has no {@code edgeGraded}: an older world's colony is not graded. */
		private static final Codec<Placed> CODEC_V1 = RecordCodecBuilder.<Placed>create(instance -> instance.group(
				BlockPos.CODEC.fieldOf("center").forGetter(Placed::center),
				Codec.unboundedMap(ColonyAnchor.CODEC, BlockPos.CODEC).fieldOf("anchors").forGetter(Placed::anchors),
				Codec.BOOL.fieldOf("finished").forGetter(Placed::finished)).apply(instance, (center, anchors, finished) -> new Placed(center, anchors, finished, true)))
				.validate(Placed::everyAnchor);

		private DataResult<Placed> everyAnchor() {
			return anchors().keySet().containsAll(List.of(ColonyAnchor.values()))
					? DataResult.success(this)
					: DataResult.error(() -> "the colony is saved without every anchor");
		}

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

	private static final Codec<Versioned<Optional<Placed>>> VERSIONED_CODEC = Versioned.codec(VERSION, Placed.CODEC.optionalFieldOf("colony"),
			Map.of(1, Placed.CODEC_V1.optionalFieldOf("colony")));

	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<ColonySite> CODEC = VERSIONED_CODEC.xmap(ColonySite::new, ColonySite::versioned);
	// Datafixer type: as for CharterData (ADR 0007), vanilla's fixers find nothing of theirs in a file that carries our own version.
	public static final SavedDataType<ColonySite> TYPE = new SavedDataType<>(ID, ColonySite::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final SavedState<Optional<Placed>> state;

	public ColonySite() {
		this.state = SavedState.fresh(ID, VERSION, Optional.empty());
	}

	private ColonySite(Versioned<Optional<Placed>> loaded) {
		this.state = SavedState.load(ID, VERSION, loaded, placed -> placed);
	}

	/** The world's colony site. Call on the server thread. */
	public static ColonySite get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	private Versioned<Optional<Placed>> versioned() {
		return state.versioned(placed -> placed);
	}

	/** False (logged once) when the saved data is of a version this build cannot read: every other method then throws. */
	public boolean isReadable() {
		return state.isReadable();
	}

	private Optional<Placed> readable() {
		return state.orThrow();
	}

	/** True once the colony has been built to the end. */
	public boolean isBuilt() {
		return readable().filter(Placed::finished).isPresent();
	}

	/** The colony, finished or not: empty only before the build begins. */
	public Optional<Placed> started() {
		return readable();
	}

	/** The built colony, or empty before it is built, and while its build is under way. */
	public Optional<Placed> placed() {
		return readable().filter(Placed::finished);
	}

	/** Records where the colony will be, before its first block. It begins once: a second call throws. */
	void begin(Placed colony) {
		if (readable().isPresent() || colony.finished()) {
			throw new IllegalStateException("the colony has begun already, or was given as finished");
		}
		state.set(Optional.of(colony));
		setDirty();
	}

	/** Marks the colony built to the end. */
	void finish() {
		Placed begun = readable().orElseThrow(() -> new IllegalStateException("the colony has not begun"));
		state.set(Optional.of(new Placed(begun.center(), begun.anchors(), true, begun.edgeGraded())));
		setDirty();
	}

	/** Records that the land around the pad is graded. */
	void gradedEdge() {
		Placed begun = readable().orElseThrow(() -> new IllegalStateException("the colony has not begun"));
		state.set(Optional.of(new Placed(begun.center(), begun.anchors(), begun.finished(), true)));
		setDirty();
	}
}
