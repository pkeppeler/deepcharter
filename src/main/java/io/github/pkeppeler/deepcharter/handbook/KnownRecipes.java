package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterEvents;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

/**
 * The recipes a charter knows, and what the server sends each player of them (ADR 0070, ADR 0066). A recipe becomes
 * known when a quest step that asks for its item becomes current ({@link HandbookChapters#current}), or when anyone on
 * the charter holds the recipe's result or one of its ingredients; a known recipe of ours also makes known the recipes
 * that make each item it takes, one step down. Every loaded recipe follows the rule, vanilla's too;
 * one that needs or makes an item the campaign world does not yield is not loaded at all ({@link ObtainableItems}, ADR
 * 0093). A player on no charter has recipes of their own ({@link KnownRecipesData}), which {@link ProgressCarry} moves
 * into a charter they found or join. Unknown recipes still craft: nothing here limits crafting.
 *
 * <p>The player's server-side recipe book holds exactly the recipes their charter knows (their own on no charter), and
 * the vanilla recipe-book packets carry it to the client. The book is made to match when it is first sent
 * ({@link #conform}), when the set grows, and when the player joins, founds or leaves a charter. {@link #knownFor} also
 * filters vanilla unlocks.
 *
 * <p>Saved data of a version this build cannot read is logged once: no recipe is known and the book is emptied, and the
 * saved data is kept ({@link #readableData}).
 */
public final class KnownRecipes {
    private KnownRecipes() {}

    /** Polls what players hold and what step they are on, and syncs the recipe book when a charter's or a player's set changes. */
    static void register() {
        CharterEvents.FOUNDED.register((server, charter) -> syncCharter(server, charter));
        CharterEvents.REVIVED.register((server, charter, director) -> syncCharter(server, charter));
        CharterEvents.JOINED.register((server, charter, player) -> syncCharter(server, charter));
        CharterEvents.LEFT.register((server, charter, player) -> syncPlayer(server, player));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RecipeIndex.clear());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.tickCount % HandbookTuning.DEFAULT.progressPollTicks() == 0) {
                    discover(server, player);
                }
            }
        });
    }

    /**
     * The ids of the recipes {@code player} knows: their charter's, or their own on no charter. Empty, and logged once, while the
     * saved charters or the saved known recipes are unreadable.
     */
    public static Set<Identifier> knownFor(MinecraftServer server, UUID player) {
        Optional<KnownRecipesData> data = readableData(server);
        if (data.isEmpty()) {
            return Set.of();
        }
        return Charters.readableCharterOf(server, player)
                .map(charter -> data.get().charter(charter.id()))
                .orElseGet(() -> data.get().player(player));
    }

    /**
     * Makes {@code player}'s server-side recipe book hold exactly the recipes they know, without telling the client. Called as
     * the book is first sent, so the client is never sent a recipe it does not know, not even one saved in the player's data.
     */
    public static void conform(ServerPlayer player) {
        reconcile(player, false);
    }

    /** Pins the recipes a charter knows after a player's discoveries are carried into it ({@link ProgressCarry}). */
    static void carry(MinecraftServer server, CharterId charter, ServerPlayer player) {
        Optional<KnownRecipesData> data = readableData(server);
        if (data.isEmpty()) {
            return;
        }
        Set<Identifier> carried = new LinkedHashSet<>(data.get().player(player.getUUID()));
        carried.addAll(found(server, player));
        data.get().addToCharter(charter, carried);
    }

    private static void discover(MinecraftServer server, ServerPlayer player) {
        Optional<KnownRecipesData> data = readableData(server);
        if (data.isEmpty()) {
            return;
        }
        Set<Identifier> found = found(server, player);
        Optional<Charter> charter = Charters.readableCharterOf(server, player.getUUID());
        if (charter.isPresent()) {
            if (data.get().addToCharter(charter.get().id(), found)) {
                syncCharter(server, charter.get());
                return;
            }
        } else {
            data.get().addToPlayer(player.getUUID(), found);
        }
        // Also undoes a recipe taken out of the book from outside (/recipe take).
        reconcile(player, true);
    }

    /** The recipes the player's inventory and current step make known. */
    private static Set<Identifier> found(MinecraftServer server, ServerPlayer player) {
        RecipeIndex index = RecipeIndex.of(server);
        Set<Identifier> found = new LinkedHashSet<>();
        Set<Item> held = new HashSet<>();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                held.add(stack.getItem());
            }
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (!carried.isEmpty()) {
            held.add(carried.getItem());
        }
        held.forEach(item -> found.addAll(index.touching(item)));
        HandbookChapters.current(server, HandbookProgress.completedFor(server, player.getUUID()))
                .ifPresent(step -> step.targets()
                        .forEach(slot -> slot.items().forEach(item -> found.addAll(index.producing(item)))));
        // A recipe of ours also shows how to make what it takes, one step down, so a crew that can see the tow cable
        // sees the lead it needs (#1602). Vanilla recipes keep the plain rule: this would otherwise unlock the tree.
        Set<Identifier> ours = new LinkedHashSet<>();
        found.stream()
                .filter(id -> id.getNamespace().equals(DeepCharter.MOD_ID))
                .forEach(ours::add);
        ours.forEach(id -> index.ingredientsOf(id).forEach(item -> found.addAll(index.producing(item))));
        return found;
    }

    private static void syncCharter(MinecraftServer server, Charter charter) {
        charter.roster().forEach(member -> syncPlayer(server, member));
    }

    private static void syncPlayer(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            reconcile(online, true);
        }
    }

    /**
     * Adds the recipes the player knows to their recipe book and removes the rest. With {@code send}, the client gets the vanilla
     * add and remove packets, with no toast and no highlight: the book is ours to fill, so a step that teaches a dozen recipes
     * makes no noise.
     */
    private static void reconcile(ServerPlayer player, boolean send) {
        MinecraftServer server = player.level().getServer();
        RecipeIndex index = RecipeIndex.of(server);
        ServerRecipeBook book = player.getRecipeBook();
        Set<ResourceKey<Recipe<?>>> have = new HashSet<>(book.pack().known());
        Set<ResourceKey<Recipe<?>>> want = new HashSet<>();
        for (Identifier id : knownFor(server, player.getUUID())) {
            ResourceKey<Recipe<?>> key = index.key(id);
            if (key != null) {
                want.add(key);
            }
        }
        List<ClientboundRecipeBookAddPacket.Entry> added = new ArrayList<>();
        List<RecipeDisplayId> removed = new ArrayList<>();
        for (ResourceKey<Recipe<?>> key : want) {
            if (!have.contains(key)) {
                book.add(key);
                server.getRecipeManager()
                        .listDisplaysForRecipe(
                                key, entry -> added.add(new ClientboundRecipeBookAddPacket.Entry(entry, false, false)));
            }
        }
        for (ResourceKey<Recipe<?>> key : have) {
            if (!want.contains(key)) {
                book.remove(key);
                server.getRecipeManager().listDisplaysForRecipe(key, entry -> removed.add(entry.id()));
            }
        }
        if (send && player.connection != null) {
            if (!added.isEmpty()) {
                player.connection.send(new ClientboundRecipeBookAddPacket(added, false));
            }
            if (!removed.isEmpty()) {
                player.connection.send(new ClientboundRecipeBookRemovePacket(removed));
            }
        }
    }

    /** The saved known recipes, or empty (logged once for each saved-data object) when they or the saved charters are unreadable. */
    private static Optional<KnownRecipesData> readableData(MinecraftServer server) {
        if (!Charters.isReadable(server)) {
            return Optional.empty();
        }
        return Optional.of(KnownRecipesData.get(server)).filter(KnownRecipesData::isReadable);
    }
}
