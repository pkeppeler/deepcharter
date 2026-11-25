package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

/**
 * Four known recipes for the catalogue tests, built by hand so a test knows exactly what the catalogue may list: a drill head
 * (3 x 3 shaped, and a second, shapeless way to make it), a rotor hub (shapeless) and an iron ingot (smelted). Their ids start high so
 * a recipe the server adds to the same client's book never takes one's place.
 */
public final class CatalogueFixtures {
    /** The first id of the fixtures. */
    private static final int FIRST_ID = 100_000;

    private CatalogueFixtures() {}

    private static SlotDisplay item(Item item) {
        return new SlotDisplay.ItemSlotDisplay(item);
    }

    private static final SlotDisplay TABLE = item(Items.CRAFTING_TABLE);
    private static final SlotDisplay NONE = SlotDisplay.Empty.INSTANCE;

    /** The fixture recipes, as the server would send them. */
    public static List<RecipeDisplayEntry> entries() {
        SlotDisplay iron = item(Items.IRON_INGOT);
        SlotDisplay copper = item(Items.COPPER_INGOT);
        SlotDisplay redstone = item(Items.REDSTONE);
        return List.of(
                new RecipeDisplayEntry(
                        new RecipeDisplayId(FIRST_ID),
                        new ShapedCraftingRecipeDisplay(
                                3,
                                3,
                                List.of(iron, redstone, iron, iron, copper, iron, iron, NONE, iron),
                                item(HangarParts.DRILL_HEAD),
                                TABLE),
                        java.util.OptionalInt.empty(),
                        RecipeBookCategories.CRAFTING_MISC,
                        Optional.of(List.of(
                                Ingredient.of(Items.IRON_INGOT),
                                Ingredient.of(Items.REDSTONE),
                                Ingredient.of(Items.IRON_INGOT),
                                Ingredient.of(Items.IRON_INGOT),
                                Ingredient.of(Items.COPPER_INGOT),
                                Ingredient.of(Items.IRON_INGOT),
                                Ingredient.of(Items.IRON_INGOT),
                                Ingredient.of(Items.IRON_INGOT)))),
                new RecipeDisplayEntry(
                        new RecipeDisplayId(FIRST_ID + 1),
                        new ShapelessCraftingRecipeDisplay(List.of(iron, iron), item(HangarParts.DRILL_HEAD), TABLE),
                        java.util.OptionalInt.empty(),
                        RecipeBookCategories.CRAFTING_MISC,
                        Optional.of(List.of(Ingredient.of(Items.IRON_INGOT), Ingredient.of(Items.IRON_INGOT)))),
                new RecipeDisplayEntry(
                        new RecipeDisplayId(FIRST_ID + 2),
                        new ShapelessCraftingRecipeDisplay(
                                List.of(iron, copper, copper), item(HangarParts.ROTOR_HUB), TABLE),
                        java.util.OptionalInt.empty(),
                        RecipeBookCategories.CRAFTING_MISC,
                        Optional.of(List.of(
                                Ingredient.of(Items.IRON_INGOT),
                                Ingredient.of(Items.COPPER_INGOT),
                                Ingredient.of(Items.COPPER_INGOT)))),
                new RecipeDisplayEntry(
                        new RecipeDisplayId(FIRST_ID + 3),
                        new FurnaceRecipeDisplay(item(Items.RAW_IRON), NONE, iron, item(Items.FURNACE), 200, 0.7f),
                        java.util.OptionalInt.empty(),
                        RecipeBookCategories.FURNACE_MISC,
                        Optional.of(List.of(Ingredient.of(Items.RAW_IRON)))));
    }

    /**
     * A fifth recipe, kept out of {@link #entries()} so the four-recipe checks stand: the hull repair nanobots, a name too long for one
     * line of the catalogue, made only in the 3 x 3 grid (eight iron round a redstone).
     */
    public static RecipeDisplayEntry nanobots() {
        SlotDisplay iron = item(Items.IRON_INGOT);
        return new RecipeDisplayEntry(
                new RecipeDisplayId(FIRST_ID + 10),
                new ShapedCraftingRecipeDisplay(
                        3,
                        3,
                        List.of(iron, iron, iron, iron, item(Items.REDSTONE), iron, iron, iron, iron),
                        item(RepairRegistry.item(Consumable.HULL_NANOBOTS)),
                        TABLE),
                java.util.OptionalInt.empty(),
                RecipeBookCategories.CRAFTING_MISC,
                Optional.of(List.of(
                        Ingredient.of(Items.IRON_INGOT),
                        Ingredient.of(Items.IRON_INGOT),
                        Ingredient.of(Items.IRON_INGOT),
                        Ingredient.of(Items.IRON_INGOT),
                        Ingredient.of(Items.REDSTONE),
                        Ingredient.of(Items.IRON_INGOT),
                        Ingredient.of(Items.IRON_INGOT),
                        Ingredient.of(Items.IRON_INGOT),
                        Ingredient.of(Items.IRON_INGOT))));
    }

    /** Every item the fixtures name: what the catalogue lists when it knows these and nothing else. */
    public static List<Item> items() {
        return List.of(
                HangarParts.DRILL_HEAD,
                HangarParts.ROTOR_HUB,
                Items.COPPER_INGOT,
                Items.IRON_INGOT,
                Items.RAW_IRON,
                Items.REDSTONE);
    }

    /** A recipe book that holds the fixtures alone. */
    public static ClientRecipeBook book() {
        ClientRecipeBook book = new ClientRecipeBook();
        fill(book);
        return book;
    }

    /** Replaces what {@code book} holds with the fixtures. */
    public static void fill(ClientRecipeBook book) {
        book.clear();
        entries().forEach(book::add);
        book.rebuildCollections();
    }
}
