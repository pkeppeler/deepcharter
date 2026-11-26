package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.handbook.ObtainableItems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import org.jspecify.annotations.Nullable;

/**
 * One recipe of the client's recipe book, resolved for the catalogue: what it makes, the grid its ingredients sit in
 * and the station it is made at. The book is the known set (ADR 0070), so a {@code KnownRecipe} is a recipe the charter
 * knows.
 *
 * @param entry the recipe book entry, whose crafting requirements decide "craftable now"
 * @param results what it makes, never empty stacks
 * @param columns the width of the ingredient grid
 * @param rows the height of the ingredient grid
 * @param cells {@code columns * rows} cells, row by row; a cell holds the obtainable items that may sit in it (a tag
 *     has several, and only the members the campaign world yields are kept, ADR 0093) and is empty for a blank
 * @param station the name of the station it is made at in capitals, or an empty string
 * @param book the kind of recipe book whose screen can make it (crafting, or the furnace it smelts in), or null for a
 *     recipe no such screen makes (stonecutting, smithing, a campfire)
 */
public record KnownRecipe(
        RecipeDisplayEntry entry,
        List<ItemStack> results,
        int columns,
        int rows,
        List<List<ItemStack>> cells,
        String station,
        @Nullable RecipeBookType book) {
    /** The widest grid of a crafting recipe, which the other displays are laid into too. */
    private static final int CRAFTING_COLUMNS = 3;

    /** The side of the inventory's crafting grid. */
    private static final int POCKET_SIDE = 2;

    /** An ingredient of the recipe and how many cells of the grid hold it. */
    public record Need(List<ItemStack> choices, int count) {}

    /** Resolves {@code entry} for display; {@code context} carries the registries a tag needs. */
    public static KnownRecipe of(RecipeDisplayEntry entry, ContextMap context) {
        RecipeDisplay display = entry.display();
        List<ItemStack> results = display.result().resolveForStacks(context).stream()
                .filter(stack -> !stack.isEmpty())
                .toList();
        List<SlotDisplay> slots;
        int columns;
        int rows;
        switch (display) {
            case ShapedCraftingRecipeDisplay shaped -> {
                slots = shaped.ingredients();
                columns = shaped.width();
                rows = shaped.height();
            }
            case ShapelessCraftingRecipeDisplay shapeless -> {
                slots = shapeless.ingredients();
                columns = Math.min(CRAFTING_COLUMNS, Math.max(1, slots.size()));
                rows = (slots.size() + columns - 1) / columns;
            }
            case FurnaceRecipeDisplay furnace -> {
                slots = List.of(furnace.ingredient());
                columns = 1;
                rows = 1;
            }
            case StonecutterRecipeDisplay cutter -> {
                slots = List.of(cutter.input());
                columns = 1;
                rows = 1;
            }
            case SmithingRecipeDisplay smithing -> {
                slots = List.of(smithing.template(), smithing.base(), smithing.addition());
                columns = CRAFTING_COLUMNS;
                rows = 1;
            }
            default -> {
                slots = List.of();
                columns = 0;
                rows = 0;
            }
        }
        List<List<ItemStack>> cells = new ArrayList<>();
        for (SlotDisplay slot : slots) {
            cells.add(slot.resolveForStacks(context).stream()
                    .filter(stack -> !stack.isEmpty() && ObtainableItems.isObtainable(stack))
                    .toList());
        }
        while (cells.size() < columns * rows) {
            cells.add(List.of());
        }
        ItemStack station = display.craftingStation().resolveForFirstStack(context);
        String stationName =
                station.isEmpty() ? "" : station.getHoverName().getString().toUpperCase(Locale.ROOT);
        return new KnownRecipe(
                entry, results, columns, rows, List.copyOf(cells), stationName, bookOf(display, station));
    }

    private static @Nullable RecipeBookType bookOf(RecipeDisplay display, ItemStack station) {
        if (display instanceof ShapedCraftingRecipeDisplay || display instanceof ShapelessCraftingRecipeDisplay) {
            return RecipeBookType.CRAFTING;
        }
        if (display instanceof FurnaceRecipeDisplay) {
            if (station.is(Items.FURNACE)) {
                return RecipeBookType.FURNACE;
            }
            if (station.is(Items.BLAST_FURNACE)) {
                return RecipeBookType.BLAST_FURNACE;
            }
            if (station.is(Items.SMOKER)) {
                return RecipeBookType.SMOKER;
            }
        }
        return null;
    }

    /** Whether it is a crafting recipe the inventory's 2 x 2 grid cannot hold (a shaped one as wide or high as its pattern, a shapeless one with a cell for each ingredient), which only a crafting table makes. */
    public boolean needsTable() {
        if (book != RecipeBookType.CRAFTING) {
            return false;
        }
        if (entry.display() instanceof ShapedCraftingRecipeDisplay) {
            return columns > POCKET_SIDE || rows > POCKET_SIDE;
        }
        // A shapeless recipe needs a cell for each ingredient.
        return cells.stream().filter(cell -> !cell.isEmpty()).count() > (long) POCKET_SIDE * POCKET_SIDE;
    }

    /** The items it makes. */
    public Set<Item> resultItems() {
        Set<Item> items = new LinkedHashSet<>();
        for (ItemStack stack : results) {
            items.add(stack.getItem());
        }
        return items;
    }

    /**
     * Every item that may sit in any cell, a tag's obtainable items all included (the rules of ADR 0070 and ADR 0093).
     */
    public Set<Item> ingredientItems() {
        Set<Item> items = new LinkedHashSet<>();
        for (List<ItemStack> cell : cells) {
            for (ItemStack stack : cell) {
                items.add(stack.getItem());
            }
        }
        return items;
    }

    /** The ingredients, cells that take the same items counted together, the most needed first and then in grid order. */
    public List<Need> needs() {
        Map<Set<Item>, List<List<ItemStack>>> groups = new LinkedHashMap<>();
        for (List<ItemStack> cell : cells) {
            if (cell.isEmpty()) {
                continue;
            }
            Set<Item> key = new LinkedHashSet<>();
            for (ItemStack stack : cell) {
                key.add(stack.getItem());
            }
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cell);
        }
        List<Need> needs = new ArrayList<>();
        for (List<List<ItemStack>> group : groups.values()) {
            needs.add(new Need(group.getFirst(), group.size()));
        }
        // The most needed first; the sort is stable, so equals keep their order in the grid.
        needs.sort(Comparator.comparingInt(Need::count).reversed());
        return needs;
    }
}
