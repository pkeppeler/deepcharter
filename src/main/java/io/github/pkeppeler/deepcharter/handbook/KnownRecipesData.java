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
 * The recipes each charter knows, and the recipes each player discovered on no charter (ADR 0070), saved with the world by recipe
 * id. A recipe id that no recipe has any more stays saved: it is filtered when sent, never dropped here. It only records:
 * {@link KnownRecipes} is what the rest of the mod calls, and {@link ProgressCarry} moves a player's set into a charter.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and every use
 * of it throws, so a newer world is never overwritten by an older build (see ADR 0007).
 */
public final class KnownRecipesData extends SavedData {
    public static final int VERSION = 1;
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "known_recipes");
    private static final Codec<Versioned<Body>> VERSIONED_CODEC = Versioned.codec(VERSION, Body.CODEC);

    /** The codec of the saved data, which never fails to decode. */
    public static final Codec<KnownRecipesData> CODEC =
            VERSIONED_CODEC.xmap(KnownRecipesData::new, KnownRecipesData::versioned);
    // Datafixer type: vanilla applies it to saved data it reads. Our data has a version of its own, so the vanilla
    // fixers find nothing to fix.
    public static final SavedDataType<KnownRecipesData> TYPE =
            new SavedDataType<>(ID, KnownRecipesData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final SavedState<Known> state;

    public KnownRecipesData() {
        this.state = SavedState.fresh(ID, VERSION, new Known(new LinkedHashMap<>(), new LinkedHashMap<>()));
    }

    private KnownRecipesData(Versioned<Body> loaded) {
        this.state = SavedState.load(ID, VERSION, loaded, body -> {
            Known known = new Known(new LinkedHashMap<>(), new LinkedHashMap<>());
            body.charters().forEach(entry -> known.charters().put(entry.owner(), new LinkedHashSet<>(entry.recipes())));
            body.players().forEach(entry -> known.players().put(entry.owner(), new LinkedHashSet<>(entry.recipes())));
            return known;
        });
    }

    /** The world's known recipes. Call on the server thread. */
    public static KnownRecipesData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /** False (logged once) when the saved data is of a version this build cannot read: every other method then throws. */
    public boolean isReadable() {
        return state.isReadable();
    }

    /** The recipes {@code charter} knows. Throws if the saved data is of a version this build cannot read. */
    public Set<Identifier> charter(CharterId charter) {
        return Set.copyOf(state.orThrow().charters().getOrDefault(charter, Set.of()));
    }

    /** The recipes {@code player} discovered on no charter. Throws if the saved data is of a version this build cannot read. */
    public Set<Identifier> player(UUID player) {
        return Set.copyOf(state.orThrow().players().getOrDefault(player, Set.of()));
    }

    /** Adds {@code recipes} to what {@code charter} knows. Returns false when it knew them all. */
    public boolean addToCharter(CharterId charter, Collection<Identifier> recipes) {
        return add(state.orThrow().charters(), charter, recipes);
    }

    /** Adds {@code recipes} to what {@code player} discovered on no charter. Returns false when they had them all. */
    public boolean addToPlayer(UUID player, Collection<Identifier> recipes) {
        return add(state.orThrow().players(), player, recipes);
    }

    private <K> boolean add(Map<K, Set<Identifier>> owners, K owner, Collection<Identifier> recipes) {
        Set<Identifier> known = owners.get(owner);
        if (known != null && known.containsAll(recipes)) {
            return false;
        }
        if (known == null) {
            known = new LinkedHashSet<>();
            owners.put(owner, known);
        }
        known.addAll(recipes);
        setDirty();
        return true;
    }

    private Versioned<Body> versioned() {
        return state.versioned(known -> new Body(
                known.charters().entrySet().stream()
                        .map(entry -> new Entry<>(entry.getKey(), List.copyOf(entry.getValue())))
                        .toList(),
                known.players().entrySet().stream()
                        .map(entry -> new Entry<>(entry.getKey(), List.copyOf(entry.getValue())))
                        .toList()));
    }

    /** What is held in memory: the recipes in the order they were learned. */
    private record Known(Map<CharterId, Set<Identifier>> charters, Map<UUID, Set<Identifier>> players) {}

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

    private record Entry<K>(K owner, List<Identifier> recipes) {
        static <K> Codec<Entry<K>> codec(Codec<K> ownerCodec, String ownerField) {
            return RecordCodecBuilder.create(instance -> instance.group(
                            ownerCodec.fieldOf(ownerField).forGetter(Entry::owner),
                            Identifier.CODEC.listOf().fieldOf("recipes").forGetter(Entry::recipes))
                    .apply(instance, Entry::new));
        }
    }
}
