package io.github.pkeppeler.deepcharter.handbook;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.SavedState;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The pencil paths each charter has completed, and the ones each player completed on no charter (ADR 0065), saved with the world by
 * path id. A path id that {@link PencilPaths} no longer has stays saved. Which paths are drawn is not here: it follows from the Notes
 * a charter has found ({@link NotesData}). It only records: {@link PencilPaths} is what the rest of the mod calls, and
 * {@link ProgressCarry} moves a player's set into a charter.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and every use
 * of it throws, so a newer world is never overwritten by an older build (see ADR 0007).
 */
public final class PencilPathsData extends SavedData {
    public static final int VERSION = 1;
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pencil_paths");
    private static final Codec<Versioned<Body>> VERSIONED_CODEC = Versioned.codec(VERSION, Body.CODEC);

    /** The codec of the saved data, which never fails to decode. */
    public static final Codec<PencilPathsData> CODEC =
            VERSIONED_CODEC.xmap(PencilPathsData::new, PencilPathsData::versioned);
    // Datafixer type: vanilla applies it to saved data it reads. Our data has a version of its own, so the vanilla
    // fixers find nothing to fix.
    public static final SavedDataType<PencilPathsData> TYPE =
            new SavedDataType<>(ID, PencilPathsData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final SavedState<Completed> state;

    public PencilPathsData() {
        this.state = SavedState.fresh(ID, VERSION, new Completed(new LinkedHashMap<>(), new LinkedHashMap<>()));
    }

    private PencilPathsData(Versioned<Body> loaded) {
        this.state = SavedState.load(ID, VERSION, loaded, body -> {
            Completed completed = new Completed(new LinkedHashMap<>(), new LinkedHashMap<>());
            body.charters()
                    .forEach(entry -> completed.charters().put(entry.owner(), new LinkedHashSet<>(entry.paths())));
            body.players().forEach(entry -> completed.players().put(entry.owner(), new LinkedHashSet<>(entry.paths())));
            return completed;
        });
    }

    /** The world's completed paths. Call on the server thread. */
    public static PencilPathsData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /** False (logged once) when the saved data is of a version this build cannot read: every other method then throws. */
    public boolean isReadable() {
        return state.isReadable();
    }

    /** The paths {@code charter} has completed. Throws if the saved data is of a version this build cannot read. */
    public Set<Identifier> charter(CharterId charter) {
        return Set.copyOf(state.orThrow().charters().getOrDefault(charter, Set.of()));
    }

    /** The paths {@code player} completed on no charter. Throws if the saved data is of a version this build cannot read. */
    public Set<Identifier> player(UUID player) {
        return Set.copyOf(state.orThrow().players().getOrDefault(player, Set.of()));
    }

    /** Adds {@code paths} to what {@code charter} has completed. Returns false when it had them all. */
    public boolean addToCharter(CharterId charter, Collection<Identifier> paths) {
        return add(state.orThrow().charters(), charter, paths);
    }

    /** Adds {@code paths} to what {@code player} completed on no charter. Returns false when they had them all. */
    public boolean addToPlayer(UUID player, Collection<Identifier> paths) {
        return add(state.orThrow().players(), player, paths);
    }

    private <K> boolean add(Map<K, Set<Identifier>> owners, K owner, Collection<Identifier> paths) {
        Set<Identifier> done = owners.get(owner);
        if (done != null && done.containsAll(paths)) {
            return false;
        }
        if (done == null) {
            done = new LinkedHashSet<>();
            owners.put(owner, done);
        }
        done.addAll(paths);
        setDirty();
        return true;
    }

    private Versioned<Body> versioned() {
        return state.versioned(completed -> new Body(
                completed.charters().entrySet().stream()
                        .map(entry -> new Entry<>(entry.getKey(), List.copyOf(entry.getValue())))
                        .toList(),
                completed.players().entrySet().stream()
                        .map(entry -> new Entry<>(entry.getKey(), List.copyOf(entry.getValue())))
                        .toList()));
    }

    /** What is held in memory: the paths in the order they were completed. */
    private record Completed(Map<CharterId, Set<Identifier>> charters, Map<UUID, Set<Identifier>> players) {}

    private record Body(List<Entry<CharterId>> charters, List<Entry<UUID>> players) {
        static final MapCodec<Body> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Entry.codec(CharterId.CODEC, "charter")
                                .listOf()
                                .fieldOf("charters")
                                .forGetter(Body::charters),
                        Entry.codec(UUIDUtil.CODEC, "player")
                                .listOf()
                                .fieldOf("players")
                                .forGetter(Body::players))
                .apply(instance, Body::new));
    }

    private record Entry<K>(K owner, List<Identifier> paths) {
        static <K> Codec<Entry<K>> codec(Codec<K> ownerCodec, String ownerField) {
            return RecordCodecBuilder.create(instance -> instance.group(
                            ownerCodec.fieldOf(ownerField).forGetter(Entry::owner),
                            Identifier.CODEC.listOf().fieldOf("paths").forGetter(Entry::paths))
                    .apply(instance, Entry::new));
        }
    }
}
