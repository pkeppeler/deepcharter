package io.github.pkeppeler.deepcharter.colony;

import java.util.Optional;
import java.util.OptionalInt;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.SavedState;
import io.github.pkeppeler.deepcharter.attachment.Versioned;

/**
 * Which block displays of the colony are still wanted, decided from saved data and not from an entity query (m2-rules.md): a query
 * finds only the displays of chunks that are loaded, so a rebuild or a second pair of the Host's hands would leave the displays of
 * a chunk that is not loaded standing beside the new ones.
 *
 * <p>The saved data counts the colony's builds (each attempt of {@link ColonyBuilder}, also one that stopped half way) and the
 * Host's hands given ({@link FounderStatue#restoreHands}). A display carries the number it was made under as an entity tag. When
 * a display loads: one without a tag in the area a build clears gets the current build's tag, and the hands get the current
 * hands' tag; one with an earlier number discards itself. The builder and {@code restoreHands} also discard the displays they find
 * loaded, so that those go at once.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable and is written back unchanged; displays are
 * then left as they are, and callbacks never throw ({@link #readable}).
 */
public final class ColonyDisplays extends SavedData {
	public static final int VERSION = 1;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "colony_displays");
	static final String BUILD_TAG = "deepcharter_colony_build_";
	static final String HANDS_TAG = "deepcharter_host_hands_";

	/** How many builds of the colony have begun, and how many times the Host has been given his hands. */
	public record State(int build, int hands) {
		private static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Codec.INT.fieldOf("build").forGetter(State::build),
				Codec.INT.fieldOf("hands").forGetter(State::hands)).apply(instance, State::new));
		static final State EMPTY = new State(0, 0);
	}

	private static final Codec<Versioned<State>> VERSIONED_CODEC = Versioned.codec(VERSION, State.BODY);
	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<ColonyDisplays> CODEC = VERSIONED_CODEC.xmap(ColonyDisplays::new, ColonyDisplays::versioned);
	// Datafixer type: as for CharterData (ADR 0007), vanilla's fixers find nothing of theirs in a file that carries our own version.
	public static final SavedDataType<ColonyDisplays> TYPE = new SavedDataType<>(ID, ColonyDisplays::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final SavedState<State> state;

	public ColonyDisplays() {
		this.state = SavedState.fresh(ID, VERSION, State.EMPTY);
	}

	private ColonyDisplays(Versioned<State> loaded) {
		this.state = SavedState.load(ID, VERSION, loaded, body -> body);
	}

	static void init() {
		ServerEntityEvents.ENTITY_LOAD.register(ColonyDisplays::onLoad);
	}

	/** The world's displays record if it is readable; otherwise empty, after logging that once. Never throws. Call on the server thread. */
	static Optional<ColonyDisplays> readable(MinecraftServer server) {
		ColonyDisplays displays = server.getDataStorage().computeIfAbsent(TYPE);
		return displays.state.isReadable() ? Optional.of(displays) : Optional.empty();
	}

	private Versioned<State> versioned() {
		return state.versioned(body -> body);
	}

	/** Starts a build of the colony: the displays of every earlier build are stale from now. */
	void beginBuild() {
		State old = state.orThrow();
		state.set(new State(old.build() + 1, old.hands()));
		setDirty();
	}

	/** Gives the Host his hands anew: the hands of every earlier pair are stale from now. */
	void beginHands() {
		State old = state.orThrow();
		state.set(new State(old.build(), old.hands() + 1));
		setDirty();
	}

	State current() {
		return state.orThrow();
	}

	private static void onLoad(Entity entity, ServerLevel level) {
		if (!(entity instanceof Display.BlockDisplay display)) {
			return;
		}
		Optional<ColonyDisplays> data = readable(level.getServer());
		if (data.isEmpty()) {
			return;
		}
		State now = data.get().current();
		boolean hands = display.getBlockState().equals(FounderStatue.handsState());
		OptionalInt build = numberOf(display, BUILD_TAG);
		OptionalInt pair = numberOf(display, HANDS_TAG);
		if (build.isPresent() && build.getAsInt() < now.build() || hands && pair.isPresent() && pair.getAsInt() < now.hands()) {
			display.discard();
			return;
		}
		if (build.isEmpty() && inClearedArea(level.getServer(), display)) {
			display.addTag(BUILD_TAG + now.build());
		}
		if (hands && pair.isEmpty()) {
			display.addTag(HANDS_TAG + now.hands());
		}
	}

	private static OptionalInt numberOf(Entity display, String prefix) {
		return display.entityTags().stream().filter(tag -> tag.matches(prefix + "\\d{1,9}")).mapToInt(tag -> Integer.parseInt(tag.substring(prefix.length()))).max();
	}

	/** Whether the display stands where a build of the colony clears the displays it finds: the pad, from its ground to the clear height. */
	private static boolean inClearedArea(MinecraftServer server, Entity display) {
		return Colony.readable(server).flatMap(ColonySite::started).filter(colony -> clearedArea(colony.center()).intersects(display.getBoundingBox())).isPresent();
	}

	/** The volume a build of the colony clears, for a pad centred on {@code centre}: the pad's size, from its ground up. */
	static AABB clearedArea(BlockPos centre) {
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		return new AABB(centre.getX() - half, centre.getY(), centre.getZ() - half, centre.getX() + half, centre.getY() + ColonyTuning.DEFAULT.clearHeight() + 1,
				centre.getZ() + half);
	}
}
