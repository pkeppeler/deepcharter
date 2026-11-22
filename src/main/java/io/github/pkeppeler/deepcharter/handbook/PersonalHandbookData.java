package io.github.pkeppeler.deepcharter.handbook;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.SavedState;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
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
 * What each player did on their own, while on no charter (ADR 0065): the directives they completed and the Notes they found,
 * saved with the world and keyed by the player's UUID. A player on a charter adds nothing here: their progress is the charter's
 * ({@link HandbookProgressData}, {@link NotesData}). {@link ProgressCarry} moves it into a charter the player founds or joins.
 * It only records: {@link HandbookProgress} and {@link Notes} are what the rest of the mod calls.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and every use
 * of it throws, so a newer world is never overwritten by an older build (see ADR 0007).
 */
public final class PersonalHandbookData extends SavedData {
    public static final int VERSION = 1;
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook_personal");
    private static final Codec<Versioned<List<Entry>>> VERSIONED_CODEC =
            Versioned.codec(VERSION, Entry.CODEC.listOf().fieldOf("players"));

    /** The codec of the saved data, which never fails to decode. */
    public static final Codec<PersonalHandbookData> CODEC =
            VERSIONED_CODEC.xmap(PersonalHandbookData::new, PersonalHandbookData::versioned);
    // Datafixer type: vanilla applies it to saved data it reads. Our data has a version of its own, so the vanilla
    // fixers find nothing to fix.
    public static final SavedDataType<PersonalHandbookData> TYPE =
            new SavedDataType<>(ID, PersonalHandbookData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final SavedState<Map<UUID, Progress>> state;

    public PersonalHandbookData() {
        this.state = SavedState.fresh(ID, VERSION, new LinkedHashMap<>());
    }

    private PersonalHandbookData(Versioned<List<Entry>> loaded) {
        this.state = SavedState.load(ID, VERSION, loaded, entries -> {
            Map<UUID, Progress> players = new LinkedHashMap<>();
            entries.forEach(entry -> players.put(
                    entry.player(),
                    new Progress(new LinkedHashSet<>(entry.directives()), new LinkedHashSet<>(entry.notes()))));
            return players;
        });
    }

    /** The world's personal progress. Call on the server thread. */
    public static PersonalHandbookData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /** False (logged once) when the saved data is of a version this build cannot read: every other method then throws. */
    public boolean isReadable() {
        return state.isReadable();
    }

    /** The directives {@code player} completed on no charter. Throws if the saved data is of a version this build cannot read. */
    public Set<Identifier> directives(UUID player) {
        return Set.copyOf(progress(player).directives());
    }

    /** The Notes {@code player} found on no charter. Throws if the saved data is of a version this build cannot read. */
    public Set<Identifier> notes(UUID player) {
        return Set.copyOf(progress(player).notes());
    }

    /** Records {@code directive} as completed by {@code player}. Returns false when it already was. */
    public boolean completeDirective(UUID player, Identifier directive) {
        boolean added = progressOrCreate(player).directives().add(directive);
        if (added) {
            setDirty();
        }
        return added;
    }

    /** Records {@code note} as found by {@code player}. Returns false when it already was. */
    public boolean addNote(UUID player, Identifier note) {
        boolean added = progressOrCreate(player).notes().add(note);
        if (added) {
            setDirty();
        }
        return added;
    }

    private Progress progress(UUID player) {
        return state.orThrow().getOrDefault(player, new Progress(Set.of(), Set.of()));
    }

    private Progress progressOrCreate(UUID player) {
        return state.orThrow()
                .computeIfAbsent(player, ignored -> new Progress(new LinkedHashSet<>(), new LinkedHashSet<>()));
    }

    private Versioned<List<Entry>> versioned() {
        return state.versioned(players -> players.entrySet().stream()
                .map(entry -> new Entry(
                        entry.getKey(),
                        List.copyOf(entry.getValue().directives()),
                        List.copyOf(entry.getValue().notes())))
                .toList());
    }

    /** One player's progress, in the order it was made. */
    private record Progress(Set<Identifier> directives, Set<Identifier> notes) {}

    private record Entry(UUID player, List<Identifier> directives, List<Identifier> notes) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        UUIDUtil.CODEC.fieldOf("player").forGetter(Entry::player),
                        Identifier.CODEC.listOf().fieldOf("directives").forGetter(Entry::directives),
                        Identifier.CODEC.listOf().fieldOf("notes").forGetter(Entry::notes))
                .apply(instance, Entry::new));
    }
}
