package io.github.pkeppeler.deepcharter.outpost;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.SavedState;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The outposts of the world: which charter built each, in which layer, and where its middle is. A charter has at most one outpost in a
 * layer. An outpost belongs to its charter (SPEC section 6) and is never destroyed (its blocks are Company property), so the list only grows.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and every use of it
 * throws, as for {@code RepairState}: callers on a callback check {@link #isReadable()} first.
 */
public final class OutpostData extends SavedData {
    public static final int VERSION = 1;
    /** The saved id of the data: never renamed, or every charter's outposts are orphaned. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "outposts");

    /**
     * One outpost.
     *
     * @param charter the charter that built it
     * @param layer   the layer it stands in (1 is the first below the surface)
     * @param centre  the middle of its hall, on the hall's floor
     */
    public record Outpost(CharterId charter, int layer, BlockPos centre) {
        private static final Codec<Outpost> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        CharterId.CODEC.fieldOf("charter").forGetter(Outpost::charter),
                        Codec.INT.fieldOf("layer").forGetter(Outpost::layer),
                        BlockPos.CODEC.fieldOf("centre").forGetter(Outpost::centre))
                .apply(instance, Outpost::new));

        /** True when {@code pos} is in the hall: inside its shell, from the floor to the ceiling. */
        public boolean contains(BlockPos pos) {
            int dy = pos.getY() - centre.getY();
            return Math.abs(pos.getX() - centre.getX()) <= OutpostBuilder.EDGE
                    && Math.abs(pos.getZ() - centre.getZ()) <= OutpostBuilder.EDGE
                    && dy >= 0
                    && dy <= OutpostBuilder.HEIGHT + 1;
        }
    }

    private static final Codec<Versioned<List<Outpost>>> VERSIONED_CODEC =
            Versioned.codec(VERSION, Outpost.CODEC.listOf().fieldOf("outposts"));

    /** The codec of the saved data, which never fails to decode. */
    public static final Codec<OutpostData> CODEC = VERSIONED_CODEC.xmap(OutpostData::new, OutpostData::versioned);
    // Datafixer type: as for RepairState, vanilla's fixers find nothing of theirs in a file that carries our own
    // version.
    public static final SavedDataType<OutpostData> TYPE =
            new SavedDataType<>(ID, OutpostData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final SavedState<List<Outpost>> state;

    public OutpostData() {
        this.state = SavedState.fresh(ID, VERSION, new ArrayList<>());
    }

    private OutpostData(Versioned<List<Outpost>> loaded) {
        this.state = SavedState.load(ID, VERSION, loaded, ArrayList::new);
    }

    /** The world's outposts. Call on the server thread. */
    public static OutpostData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    private Versioned<List<Outpost>> versioned() {
        return state.versioned(List::copyOf);
    }

    /** False (logged once) when the saved data is of a version this build cannot read: every other method then throws. */
    public boolean isReadable() {
        return state.isReadable();
    }

    /** The charter's outpost in {@code layer}, if it has built one. */
    public Optional<Outpost> of(CharterId charter, int layer) {
        return state.orThrow().stream()
                .filter(outpost -> outpost.charter().equals(charter) && outpost.layer() == layer)
                .findFirst();
    }

    /**
     * The first outpost in {@code layer}, of any charter, whose hall a hall with its floor middle at {@code centre} would touch: the
     * middles are within two edges of each other, level by level, in both x and z.
     */
    public Optional<Outpost> overlapping(int layer, BlockPos centre) {
        return state.orThrow().stream()
                .filter(outpost -> outpost.layer() == layer)
                .filter(outpost -> Math.max(
                                Math.abs(outpost.centre().getX() - centre.getX()),
                                Math.abs(outpost.centre().getZ() - centre.getZ()))
                        <= 2 * OutpostBuilder.EDGE)
                .findFirst();
    }

    /** Records an outpost. Throws when the charter has one in that layer already: the kit checks first. */
    void add(Outpost outpost) {
        if (of(outpost.charter(), outpost.layer()).isPresent()) {
            throw new IllegalStateException(
                    "charter " + outpost.charter() + " has an outpost in layer " + outpost.layer() + " already");
        }
        state.orThrow().add(outpost);
        setDirty();
    }
}
