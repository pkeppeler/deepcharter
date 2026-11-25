package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

/**
 * What the charter knows, as the catalogue shows it. The known recipes are the client's recipe book, which the server fills with exactly
 * the charter's known recipes (ADR 0070); a <em>known item</em> is an item such a recipe makes, or an item that may sit in one of its
 * ingredient cells (every item of a tag), the rule the server uses to teach a recipe. An item with no known recipe is not listed, and a
 * recipe the book lacks is never shown, so the catalogue reveals nothing the book does not.
 */
public final class PartsCatalogue {
    /** Company parts first, then by id, so the catalogue opens on the parts and not on a screenful of vanilla blocks. */
    private static final Comparator<Item> ORDER = Comparator.comparing((Item item) -> !isOurs(item))
            .thenComparing(item -> BuiltInRegistries.ITEM.getKey(item).toString());

    private final List<Item> items;
    private final Map<Item, List<KnownRecipe>> making = new HashMap<>();
    private final Map<Item, List<KnownRecipe>> using = new HashMap<>();

    private PartsCatalogue(List<KnownRecipe> recipes) {
        Set<Item> known = new LinkedHashSet<>();
        for (KnownRecipe recipe : recipes) {
            for (Item item : recipe.resultItems()) {
                known.add(item);
                making.computeIfAbsent(item, ignored -> new ArrayList<>()).add(recipe);
            }
            for (Item item : recipe.ingredientItems()) {
                known.add(item);
                using.computeIfAbsent(item, ignored -> new ArrayList<>()).add(recipe);
            }
        }
        this.items = known.stream().sorted(ORDER).toList();
    }

    private static boolean isOurs(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(DeepCharter.MOD_ID);
    }

    /** The catalogue of every recipe in {@code book}; {@code context} resolves their displays ({@code SlotDisplayContext.fromLevel}). */
    public static PartsCatalogue of(ClientRecipeBook book, ContextMap context) {
        List<RecipeDisplayEntry> entries = new ArrayList<>();
        for (RecipeCollection collection : book.getCollections()) {
            entries.addAll(collection.getRecipes());
        }
        return of(entries, context);
    }

    /** The catalogue of {@code entries}, each resolved for display, in id order; one that makes nothing is left out. */
    public static PartsCatalogue of(List<RecipeDisplayEntry> entries, ContextMap context) {
        // By id, the order the server numbered the recipes in, so R steps through an item's recipes in the same order
        // every time
        // (the book is a hash map).
        List<RecipeDisplayEntry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingInt(shown -> shown.id().index()));
        List<KnownRecipe> recipes = new ArrayList<>();
        for (RecipeDisplayEntry entry : ordered) {
            KnownRecipe recipe = KnownRecipe.of(entry, context);
            if (!recipe.results().isEmpty()) {
                recipes.add(recipe);
            }
        }
        return new PartsCatalogue(List.copyOf(recipes));
    }

    /** Every known item, Company parts first. */
    public List<Item> items() {
        return items;
    }

    /** The known recipes that make {@code item}. */
    public List<KnownRecipe> recipesFor(Item item) {
        return making.getOrDefault(item, List.of());
    }

    /** The known recipes that take {@code item} in an ingredient cell. */
    public List<KnownRecipe> usesOf(Item item) {
        return using.getOrDefault(item, List.of());
    }

    /**
     * Whether a known recipe that makes {@code item} is of the kind {@code bench} makes (crafting, or the furnace it smelts in) and
     * {@code contents} holds what it takes. The grid does not matter: a 3 x 3 recipe is craftable now at the 2 x 2 inventory, made at a
     * table.
     */
    public boolean craftable(Item item, StackedItemContents contents, Bench bench) {
        return craftableRecipes(item, contents, bench).findAny().isPresent();
    }

    /** Whether {@code item} is craftable now only at a crafting table: every recipe that is makes needs the 3 x 3 grid. */
    public boolean tableOnly(Item item, StackedItemContents contents, Bench bench) {
        List<KnownRecipe> now = craftableRecipes(item, contents, bench).toList();
        return !now.isEmpty() && now.stream().allMatch(KnownRecipe::needsTable);
    }

    /** Every item {@link #tableOnly} holds for: worked out once for a whole catalogue, not for each cell drawn. */
    public Set<Item> tableOnlyItems(StackedItemContents contents, Bench bench) {
        Set<Item> items = new HashSet<>();
        for (Item item : items()) {
            if (tableOnly(item, contents, bench)) {
                items.add(item);
            }
        }
        return items;
    }

    private Stream<KnownRecipe> craftableRecipes(Item item, StackedItemContents contents, Bench bench) {
        return recipesFor(item).stream()
                .filter(recipe ->
                        recipe.book() == bench.type() && recipe.entry().canCraft(contents));
    }
}
