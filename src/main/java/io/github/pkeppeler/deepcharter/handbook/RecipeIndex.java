package io.github.pkeppeler.deepcharter.handbook;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jspecify.annotations.Nullable;

/**
 * Which recipes an item belongs to, for the recipes that can be in a recipe book (every one but the special ones). Built from the
 * server's recipe manager, and built again when {@code /reload} replaces it, so a datapack's recipes follow the same rules as
 * vanilla's (ADR 0070).
 *
 * <p>An item <em>touches</em> a recipe when the recipe makes it, or when it is one of the recipe's ingredients (every item an
 * ingredient tag names). It <em>produces</em> a recipe when the recipe makes it.
 */
public final class RecipeIndex {
    private static @Nullable RecipeIndex cached;

    private final RecipeManager manager;
    private final Map<Item, Set<Identifier>> touching = new HashMap<>();
    private final Map<Item, Set<Identifier>> producing = new HashMap<>();
    private final Map<Identifier, ResourceKey<Recipe<?>>> keys = new HashMap<>();

    private RecipeIndex(MinecraftServer server, RecipeManager manager) {
        this.manager = manager;
        var context = SlotDisplayContext.fromLevel(server.overworld());
        for (RecipeHolder<?> holder : manager.getLearnableRecipes()) {
            Identifier id = holder.id().identifier();
            keys.put(id, holder.id());
            for (Ingredient ingredient : holder.value().placementInfo().ingredients()) {
                for (ItemStack stack : ingredient.display().resolveForStacks(context)) {
                    touching.computeIfAbsent(stack.getItem(), ignored -> new HashSet<>())
                            .add(id);
                }
            }
            manager.listDisplaysForRecipe(holder.id(), entry -> {
                for (ItemStack result : entry.resultItems(context)) {
                    if (!result.isEmpty()) {
                        touching.computeIfAbsent(result.getItem(), ignored -> new HashSet<>())
                                .add(id);
                        producing
                                .computeIfAbsent(result.getItem(), ignored -> new HashSet<>())
                                .add(id);
                    }
                }
            });
        }
    }

    /** The index of the server's current recipes. Call on the server thread. */
    static RecipeIndex of(MinecraftServer server) {
        return of(server, server.getRecipeManager());
    }

    /**
     * The index of {@code manager}'s recipes: the one built before when it is the same manager, and a new one when it is not, as
     * after {@code /reload}, which replaces the manager.
     */
    // test-seam: the /reload rebuild is tested by passing a replaced manager
    public static RecipeIndex of(MinecraftServer server, RecipeManager manager) {
        RecipeIndex index = cached;
        if (index == null || index.manager != manager) {
            index = new RecipeIndex(server, manager);
            cached = index;
        }
        return index;
    }

    /** Drops the index, which would otherwise keep the last server's recipes alive after it stops. */
    static void clear() {
        cached = null;
    }

    /** The recipes {@code item} is the result or an ingredient of. */
    Set<Identifier> touching(Item item) {
        return touching.getOrDefault(item, Set.of());
    }

    /** The recipes that make {@code item}. */
    Set<Identifier> producing(Item item) {
        return producing.getOrDefault(item, Set.of());
    }

    /** The key of the recipe with {@code id}, if it exists and can be in a recipe book. */
    @Nullable
    ResourceKey<Recipe<?>> key(Identifier id) {
        return keys.get(id);
    }
}
