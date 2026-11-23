package io.github.pkeppeler.deepcharter.layer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.SavedState;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The fresh chunks of a level that holds layers that generated before the colony's pad was chosen, so were not drawn: held here until
 * {@link LayerStructures} draws each one, once, and removes it (ADR 0025, #913). It is saved, because a chunk saved undrawn reloads with
 * {@code newlyGenerated} false and would never be drawn if a server stop lost the list.
 *
 * <p>It is its own record, not a part of {@code ColonySite}: a test that swaps the colony's record would hide chunks added meanwhile.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable and is written back unchanged; nothing is then
 * held or drawn, and callbacks never throw ({@link #readable}).
 */
public final class PendingStructures extends SavedData {
    public static final int VERSION = 1;
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pending_structures");

    private record Chunk(int x, int z) {
        private static final Codec<Chunk> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.INT.fieldOf("x").forGetter(Chunk::x),
                        Codec.INT.fieldOf("z").forGetter(Chunk::z))
                .apply(instance, Chunk::new));
    }

    /** The chunks held in one level, saved by the level's id. */
    private record Entry(Identifier dimension, List<Chunk> chunks) {
        private static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Identifier.CODEC.fieldOf("dimension").forGetter(Entry::dimension),
                        Chunk.CODEC.listOf().fieldOf("chunks").forGetter(Entry::chunks))
                .apply(instance, Entry::new));
    }

    private static final Codec<Versioned<List<Entry>>> VERSIONED_CODEC =
            Versioned.codec(VERSION, Entry.CODEC.listOf().fieldOf("levels"));

    /** The codec of the saved data, which never fails to decode. */
    public static final Codec<PendingStructures> CODEC =
            VERSIONED_CODEC.xmap(PendingStructures::new, PendingStructures::versioned);
    // Datafixer type: as for CharterData (ADR 0007), vanilla's fixers find nothing of theirs in a file that carries our
    // own version.
    public static final SavedDataType<PendingStructures> TYPE =
            new SavedDataType<>(ID, PendingStructures::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final SavedState<Map<Identifier, Set<ChunkPos>>> state;

    public PendingStructures() {
        this.state = SavedState.fresh(ID, VERSION, new LinkedHashMap<>());
    }

    private PendingStructures(Versioned<List<Entry>> loaded) {
        this.state = SavedState.load(ID, VERSION, loaded, entries -> {
            Map<Identifier, Set<ChunkPos>> held = new LinkedHashMap<>();
            for (Entry entry : entries) {
                Set<ChunkPos> chunks = held.computeIfAbsent(entry.dimension(), key -> new LinkedHashSet<>());
                entry.chunks().forEach(chunk -> chunks.add(new ChunkPos(chunk.x(), chunk.z())));
            }
            return held;
        });
    }

    private List<Entry> body(Map<Identifier, Set<ChunkPos>> held) {
        List<Entry> entries = new ArrayList<>();
        held.forEach((dimension, chunks) -> {
            if (!chunks.isEmpty()) {
                entries.add(new Entry(
                        dimension,
                        chunks.stream().map(pos -> new Chunk(pos.x(), pos.z())).toList()));
            }
        });
        return entries;
    }

    private Versioned<List<Entry>> versioned() {
        return state.versioned(this::body);
    }

    /** The world's pending chunks if readable; otherwise empty, after logging that once. Never throws. Call on the server thread. */
    static Optional<PendingStructures> readable(MinecraftServer server) {
        PendingStructures pending = server.getDataStorage().computeIfAbsent(TYPE);
        return pending.state.isReadable() ? Optional.of(pending) : Optional.empty();
    }

    /** Holds a chunk of {@code level} until it is drawn. */
    void add(ResourceKey<Level> level, ChunkPos chunk) {
        if (state.orThrow()
                .computeIfAbsent(level.identifier(), key -> new LinkedHashSet<>())
                .add(chunk)) {
            setDirty();
        }
    }

    /** Releases a chunk that was drawn, or is given up. */
    void remove(ResourceKey<Level> level, ChunkPos chunk) {
        Map<Identifier, Set<ChunkPos>> held = state.orThrow();
        Set<ChunkPos> chunks = held.get(level.identifier());
        if (chunks != null && chunks.remove(chunk)) {
            if (chunks.isEmpty()) {
                held.remove(level.identifier());
            }
            setDirty();
        }
    }

    /** The held chunks by level, as a copy that later changes do not touch. */
    Map<ResourceKey<Level>, List<ChunkPos>> snapshot() {
        Map<ResourceKey<Level>, List<ChunkPos>> copy = new LinkedHashMap<>();
        state.orThrow()
                .forEach((dimension, chunks) ->
                        copy.put(ResourceKey.create(Registries.DIMENSION, dimension), List.copyOf(chunks)));
        return copy;
    }
}
