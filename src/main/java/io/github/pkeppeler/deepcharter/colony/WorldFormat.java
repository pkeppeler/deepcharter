package io.github.pkeppeler.deepcharter.colony;

import com.mojang.serialization.Codec;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.SavedState;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The world-format stamp (ADR 0059): the number of the world format a world was made in. {@link ColonyEvents#BUILT} writes
 * {@link #CURRENT}, and {@link WorldFormatGate} refuses to start a world that has a colony and a lower number (none counts as {@link #LEGACY}).
 *
 * <p>The saved-data id {@code deepcharter:world_format} is permanent. A build that renamed it would find no stamp in every
 * world made so far and refuse them all. The number is not the record's {@link #VERSION}: the number says which world layout the
 * chunks have (the one-line list of formats is on {@link #CURRENT}), the version says how the record is written.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and the gate
 * refuses the world, because a stamp that cannot be read cannot vouch for the world.
 */
public final class WorldFormat extends SavedData {
    /** The version of the saved record. */
    public static final int VERSION = 1;

    /**
     * The world format this build makes and runs. 1 is the format of a world whose overworld is the 384-tall surface with the layer
     * dimensions beside it; 2 is the format of the 2352-tall campaign overworld (#803, ADR 0059); 3 is that overworld with layer 3 built
     * where unbuilt depth was (#972: each release that adds a layer needs a new world). A world with a colony and a lower number is refused.
     */
    public static final int CURRENT = 3;

    /**
     * The format of a world with no stamp: every world made before the stamp existed. Those worlds are format 1, so they are refused.
     */
    public static final int LEGACY = 1;

    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "world_format");

    private static final Codec<Versioned<Optional<Integer>>> VERSIONED_CODEC =
            Versioned.codec(VERSION, Codec.INT.optionalFieldOf("format"));

    /** The codec of the saved data, which never fails to decode. */
    public static final Codec<WorldFormat> CODEC = VERSIONED_CODEC.xmap(WorldFormat::new, WorldFormat::versioned);

    // Datafixer type: as for ColonySite, vanilla's fixers find nothing of theirs in a file that carries our
    // own version.
    public static final SavedDataType<WorldFormat> TYPE =
            new SavedDataType<>(ID, WorldFormat::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    /** The stamped number, or empty before the world is stamped. */
    private final SavedState<Optional<Integer>> state;

    public WorldFormat() {
        this.state = SavedState.fresh(ID, VERSION, Optional.empty());
    }

    private WorldFormat(Versioned<Optional<Integer>> loaded) {
        this.state = SavedState.load(ID, VERSION, loaded, format -> format);
    }

    static void init() {
        ColonyEvents.BUILT.register(WorldFormat::stampBuilt);
    }

    /** An unreadable record is logged once by {@code isReadable} and left as it is: a throw would stop the server start. */
    private static void stampBuilt(MinecraftServer server, ColonySite.Placed colony) {
        WorldFormat stamp = get(server);
        if (stamp.isReadable()) {
            stamp.stamp();
        }
    }

    /** The world's stamp. Call on the server thread. */
    public static WorldFormat get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    private Versioned<Optional<Integer>> versioned() {
        return state.versioned(format -> format);
    }

    /** False (logged once) when the saved record is of a version this build cannot read: {@link #format} then throws. */
    public boolean isReadable() {
        return state.isReadable();
    }

    /** The stamped format number, or empty when the world has no stamp. Throws if the record is unreadable. */
    public OptionalInt format() {
        return state.orThrow().map(OptionalInt::of).orElseGet(OptionalInt::empty);
    }

    /** Stamps the world with {@link #CURRENT}. Throws if the record is unreadable, so the saved data is never overwritten. */
    void stamp() {
        state.set(Optional.of(CURRENT));
        setDirty();
    }
}
