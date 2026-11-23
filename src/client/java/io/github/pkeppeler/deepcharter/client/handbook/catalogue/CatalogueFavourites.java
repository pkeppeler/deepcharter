package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.DeepCharter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * The items a player has starred in the catalogue, kept on this client in {@code config/deepcharter-parts-catalogue.json} and told apart
 * by player, so two accounts on one machine keep their own. The file is {@code {"version": 1, "players": {"<uuid>": ["<item id>", ...]}}}.
 * A file of another version, or one that does not parse, is read as empty and never written over (a newer build may own it), so the
 * favourites of that run live in memory only. An id that names no item, or does not parse, stays in the file as written and is skipped when listed.
 */
public final class CatalogueFavourites {
    /** The version of the file this build reads and writes. */
    static final int VERSION = 1;

    private static @Nullable CatalogueFavourites shared;

    private final Path file;
    private final Map<UUID, List<String>> players = new LinkedHashMap<>();
    private boolean writable = true;

    private CatalogueFavourites(Path file) {
        this.file = file;
        load();
    }

    /** The favourites file of this client, read the first time it is asked for. */
    public static synchronized CatalogueFavourites shared() {
        CatalogueFavourites favourites = shared;
        if (favourites == null) {
            Path config = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
            favourites = in(config.resolve(DeepCharter.MOD_ID + "-parts-catalogue.json"));
            shared = favourites;
        }
        return favourites;
    }

    /** The favourites kept in {@code file}, which need not exist yet. */
    public static CatalogueFavourites in(Path file) {
        return new CatalogueFavourites(file);
    }

    /** The favourite items of {@code player}, in the order they were starred. */
    public synchronized List<Item> items(UUID player) {
        List<Item> items = new ArrayList<>();
        for (String raw : players.getOrDefault(player, List.of())) {
            Identifier id = Identifier.tryParse(raw);
            if (id != null) {
                BuiltInRegistries.ITEM.getOptional(id).ifPresent(items::add);
            }
        }
        return items;
    }

    /**
     * Stars {@code item} for {@code player}, or takes the star off; returns whether it is a favourite now. The file is read again first, so
     * another client on the same machine that starred something meanwhile keeps its star.
     */
    public synchronized boolean toggle(UUID player, Item item) {
        if (writable) {
            players.clear();
            load();
        }
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        List<String> ids = players.computeIfAbsent(player, ignored -> new ArrayList<>());
        boolean starred = !ids.remove(id);
        if (starred) {
            ids.add(id);
        }
        save();
        return starred;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("version") || root.get("version").getAsInt() != VERSION) {
                writable = false;
                DeepCharter.LOGGER.warn(
                        "The parts catalogue favourites file {} has another version; favourites stay in memory", file);
                return;
            }
            for (Map.Entry<String, JsonElement> player :
                    root.getAsJsonObject("players").entrySet()) {
                // An id that names no item, or does not parse, is kept as it was written: a newer build may know it.
                List<String> ids = new ArrayList<>();
                for (JsonElement id : player.getValue().getAsJsonArray()) {
                    ids.add(id.getAsString());
                }
                players.put(UUID.fromString(player.getKey()), ids);
            }
        } catch (IOException
                | JsonParseException
                | IllegalStateException
                | IllegalArgumentException
                | UnsupportedOperationException
                | ClassCastException
                | NullPointerException e) {
            // A file that does not read is left as it is, so nothing is lost to a truncated write of an older run.
            writable = false;
            players.clear();
            DeepCharter.LOGGER.warn(
                    "The parts catalogue favourites file {} cannot be read; they stay in memory", file, e);
        }
    }

    private void save() {
        if (!writable) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject all = new JsonObject();
        players.forEach((player, ids) -> {
            JsonArray array = new JsonArray();
            ids.forEach(id -> array.add(id.toString()));
            all.add(player.toString(), array);
        });
        root.add("players", all);
        try {
            Files.createDirectories(file.getParent());
            // Written beside and moved over, so a crash never leaves half a file.
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            DeepCharter.LOGGER.warn("Cannot write the parts catalogue favourites to {}; they stay in memory", file, e);
        }
    }
}
