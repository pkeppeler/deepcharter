package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.recipebook.PlaceRecipeHelper;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;

/**
 * What the recipe card's fill switch does with a recipe on the screen the catalogue stands beside: fill the grid (vanilla's recipe-book
 * placement, which the server does), show the recipe as a ghost over the grid with the slots the player cannot fill marked, or say why it
 * does neither. The grid is worked out here, from {@link KnownRecipe#needsTable()} and the bench's width, because "craftable now" counts a
 * 3 x 3 recipe at the 2 x 2 inventory (#1258).
 */
final class RecipeFill {
    /** The side of the crafting table's grid, and so the least a {@linkplain KnownRecipe#needsTable() table-only} recipe fits. */
    private static final int TABLE_SIDE = 3;

    private RecipeFill() {}

    /** What the switch does, and the lang key (under {@code deepcharter.catalogue.}) of its legend. */
    enum Fit {
        /** The player holds the ingredients and the grid takes the recipe: the server fills the grid. */
        FILL("fill"),
        /** The grid takes the recipe but the player lacks some ingredients: it is shown as a ghost over the grid. */
        GHOST("fill.ghost"),
        /** A crafting recipe of the 3 x 3 grid, on a 2 x 2 one. */
        NEEDS_TABLE("fill.table"),
        /** A recipe this screen does not make (a furnace's on the inventory, a stonecutter's anywhere). */
        NOT_HERE("fill.here");

        private final String legendKey;

        Fit(String legendKey) {
            this.legendKey = legendKey;
        }

        String legendKey() {
            return legendKey;
        }
    }

    /** One ingredient cell of a recipe: the grid slot it goes in (an index of the menu's input slots) and the items that may sit there. */
    record Placed(int slot, List<ItemStack> choices) {}

    /** What the switch does with {@code recipe} at {@code bench} when the player holds {@code held}. */
    static Fit fit(KnownRecipe recipe, Bench bench, StackedItemContents held) {
        RecipeBookType book = recipe.book();
        if (book == null || book != bench.type()) {
            return Fit.NOT_HERE;
        }
        if (recipe.needsTable() && bench.width() < TABLE_SIDE) {
            return Fit.NEEDS_TABLE;
        }
        return recipe.entry().canCraft(held) ? Fit.FILL : Fit.GHOST;
    }

    /**
     * Where each ingredient cell of {@code recipe} goes in a grid {@code width} by {@code height}: a shaped recipe is centred in it the
     * way the recipe book places it, a shapeless one takes the first slots in turn; a blank cell takes none.
     */
    static List<Placed> place(KnownRecipe recipe, int width, int height) {
        List<Placed> placed = new ArrayList<>();
        if (recipe.entry().display() instanceof ShapedCraftingRecipeDisplay) {
            PlaceRecipeHelper.placeRecipe(
                    width, height, recipe.columns(), recipe.rows(), recipe.cells(), (cell, slot, x, y) -> {
                        if (!cell.isEmpty()) {
                            placed.add(new Placed(slot, cell));
                        }
                    });
            return List.copyOf(placed);
        }
        int slot = 0;
        for (List<ItemStack> cell : recipe.cells()) {
            if (!cell.isEmpty() && slot < width * height) {
                placed.add(new Placed(slot++, cell));
            }
        }
        return List.copyOf(placed);
    }

    /** The slots of {@code cells} that {@code held} (how many of each item the player has, grid included) cannot fill, each cell taking its own. */
    static Set<Integer> missing(List<Placed> cells, Map<Item, Integer> held) {
        Map<Item, Integer> left = new HashMap<>(held);
        Set<Integer> missing = new TreeSet<>();
        for (Placed cell : cells) {
            Item taken = null;
            for (ItemStack choice : cell.choices()) {
                if (left.getOrDefault(choice.getItem(), 0) > 0) {
                    taken = choice.getItem();
                    break;
                }
            }
            if (taken == null) {
                missing.add(cell.slot());
            } else {
                left.merge(taken, -1, Integer::sum);
            }
        }
        return missing;
    }
}
