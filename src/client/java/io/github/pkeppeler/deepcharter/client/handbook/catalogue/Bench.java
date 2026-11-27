package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.RecipeBookType;

/**
 * Where the player can make things on the screen the catalogue stands beside: which kind of recipe book the screen has (crafting, furnace,
 * blast furnace, smoker) and the size of its crafting grid (2 x 2 in the inventory, 3 x 3 at a crafting table, 1 x 1 elsewhere).
 * "Craftable now" means craftable here.
 *
 * @param type the screen's recipe book
 * @param width the crafting grid's width
 * @param height the crafting grid's height
 */
public record Bench(RecipeBookType type, int width, int height) {
    public static Bench of(RecipeBookMenu menu) {
        if (menu instanceof AbstractCraftingMenu crafting) {
            return new Bench(menu.getRecipeBookType(), crafting.getGridWidth(), crafting.getGridHeight());
        }
        return new Bench(menu.getRecipeBookType(), 1, 1);
    }
}
