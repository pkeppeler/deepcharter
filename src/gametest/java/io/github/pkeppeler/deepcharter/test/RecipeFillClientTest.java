package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.KnownRecipe;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.FillScenes;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Client GameTest for #1259's fill and ghost: the recipe card's switch on a real server's recipes. Holding the ingredients, a press moves
 * them into the open grid (vanilla's recipe-book placement, so the server does it), at the 2 x 2 inventory and at the 3 x 3 table, and the
 * result appears; lacking some, a press moves nothing and shows the recipe as a ghost with the missing slots marked; a 3 x 3 recipe on
 * the 2 x 2 grid says it needs a crafting table. Every press is a real click through {@code MouseHandler}.
 */
public class RecipeFillClientTest implements FabricClientGameTest {
    private static final int GRID_SLOTS = 9;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player's inventory", client -> client.player != null && client.level != null);
            singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 9");
            // A charter knows the recipes of what it holds (ADR 0070): the iron teaches the pickaxe and the smelting, a
            // stick its own.
            singleplayer.getServer().runCommand("give @p minecraft:stick 1");
            CatalogueScene.openInventory(context);
            CataloguePanel panel = CatalogueScene.panel(context);
            ClientWait.until(
                    context,
                    "the recipes the iron teaches",
                    client -> FillScenes.crafting(panel, Items.IRON_BLOCK) != null
                            && FillScenes.crafting(panel, Items.IRON_NUGGET) != null);
            ClientWait.until(
                    context, "the stick's crafting recipe", client -> FillScenes.crafting(panel, Items.STICK) != null);
            ClientWait.until(
                    context,
                    "the iron pickaxe's crafting recipe",
                    client -> FillScenes.crafting(panel, Items.IRON_PICKAXE) != null);
            ClientWait.until(
                    context,
                    "the iron ingot's smelting recipe",
                    client -> FillScenes.recipe(panel, Items.IRON_INGOT, RecipeBookType.FURNACE) != null);
            context.setScreen(() -> null);

            aFullHandFillsTheInventoryGrid(context, singleplayer);
            aFullHandFillsTheTable(context, singleplayer);
            aShortHandShowsAGhostAndMovesNothing(context, singleplayer);
            aTableRecipeOnTheInventoryGridNeedsATable(context, singleplayer);
            theNoteGoesWithItsRecipe(context, singleplayer);
            theGhostGoesWhenItsRecipeDoes(context, singleplayer);
            theGhostGoesWhenThePanelsArePutAway(context, singleplayer);
            aFurnaceRecipeFillsTheFurnace(context, singleplayer);
            aFurnaceRecipeIsNotHereAtTheTable(context, singleplayer);
            aPartialShapedRecipeIsGhostedWhereTheServerPlacesIt(context, singleplayer);
            aShiftPressFillsTheMost(context, singleplayer);
            aRefusedFillSaysSo(context, singleplayer);
            context.setScreen(() -> null);
            singleplayer.getServer().runCommand("clear @p");
        }
    }

    // ---------------------------------------------------------------- the scenes

    private static int count(List<ItemStack> stacks, Item item) {
        return FillScenes.count(stacks, item);
    }

    private static List<Slot> grid(Minecraft client) {
        return ((AbstractCraftingMenu)
                        ((AbstractContainerScreen<?>) Objects.requireNonNull(client.gui.screen())).getMenu())
                .getInputGridSlots();
    }

    private static ItemStack result(Minecraft client) {
        return ((AbstractCraftingMenu)
                        ((AbstractContainerScreen<?>) Objects.requireNonNull(client.gui.screen())).getMenu())
                .getResultSlot()
                .getItem();
    }

    private static boolean gridEmpty(Minecraft client) {
        return grid(client).stream().allMatch(slot -> slot.getItem().isEmpty());
    }

    // ---------------------------------------------------------------- the cases

    private static void aFullHandFillsTheInventoryGrid(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CataloguePanel panel = FillScenes.inventoryHolding(context, singleplayer, GRID_SLOTS);
        FillScenes.showCrafting(context, panel, Items.IRON_NUGGET);
        require(
                context.computeOnClient(client -> panel.fillLegend()).equals("FILL GRID"),
                "holding the iron, the switch offers to fill, got "
                        + context.computeOnClient(client -> panel.fillLegend()));
        FillScenes.pressFill(context, panel);
        ClientWait.until(
                context,
                "an iron ingot in the first slot of the 2 x 2 grid and the nuggets in the result",
                client -> grid(client).getFirst().getItem().is(Items.IRON_INGOT)
                        && result(client).is(Items.IRON_NUGGET));
        require(
                context.computeOnClient(client -> grid(client).size()) == 4,
                "the inventory's grid is 2 x 2, "
                        + context.computeOnClient(client -> grid(client).size()) + " slots");
        require(
                context.computeOnClient(client -> count(
                                ClientSession.player(client).getInventory().getNonEquipmentItems(), Items.IRON_INGOT))
                        == GRID_SLOTS - 1,
                "the ingredient moved out of the inventory, one iron into the grid");
        require(!context.computeOnClient(client -> panel.ghostShown()), "a fill leaves no ghost");
    }

    private static void aFullHandFillsTheTable(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CataloguePanel panel = FillScenes.tableHolding(context, singleplayer, GRID_SLOTS);
        FillScenes.showCrafting(context, panel, Items.IRON_BLOCK);
        require(
                context.computeOnClient(client -> panel.fillLegend()).equals("FILL GRID"),
                "holding nine iron at the table, the switch offers to fill");
        FillScenes.pressFill(context, panel);
        ClientWait.until(
                context,
                "an iron ingot in each of the table's nine slots and the iron block in the result",
                client -> grid(client).size() == GRID_SLOTS
                        && grid(client).stream().allMatch(slot -> slot.getItem().is(Items.IRON_INGOT))
                        && result(client).is(Items.IRON_BLOCK));
        require(
                context.computeOnClient(client -> count(
                                ClientSession.player(client).getInventory().getNonEquipmentItems(), Items.IRON_INGOT))
                        == 0,
                "all nine iron left the inventory");
    }

    private static void aShortHandShowsAGhostAndMovesNothing(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CataloguePanel panel = FillScenes.tableHolding(context, singleplayer, 3);
        FillScenes.showCrafting(context, panel, Items.IRON_BLOCK);
        require(
                context.computeOnClient(client -> panel.fillLegend()).equals("SHOW IN GRID"),
                "holding three of nine iron, the switch offers the ghost, got "
                        + context.computeOnClient(client -> panel.fillLegend()));
        FillScenes.pressFill(context, panel);
        ClientWait.until(context, "the ghost over the grid", client -> panel.ghostShown());
        require(
                context.computeOnClient(client -> panel.ghostMissing()).equals(Set.of(3, 4, 5, 6, 7, 8)),
                "the six slots the three iron cannot fill are marked, the first three are not, got "
                        + context.computeOnClient(client -> panel.ghostMissing()));
        require(
                context.computeOnClient(
                        client -> gridEmpty(client) && result(client).isEmpty()),
                "a ghost moves nothing into the grid");
        require(
                context.computeOnClient(client -> count(
                                ClientSession.player(client).getInventory().getNonEquipmentItems(), Items.IRON_INGOT))
                        == 3,
                "and the three iron stay in the inventory");

        // An item that teaches new recipes rebuilds the catalogue under the ghost, and the ghost stands.
        // String, which the world yields (salvage cloth), teaches wool and the bow; an unobtainable item has no
        // recipes (ADR 0093).
        Object before = context.computeOnClient(client -> panel.catalogue());
        singleplayer.getServer().runCommand("give @p minecraft:string 1");
        ClientWait.until(context, "the catalogue rebuilt", client -> panel.catalogue() != before);
        context.waitTicks(2);
        require(
                context.computeOnClient(
                        client -> panel.ghostShown() && panel.ghostMissing().size() == 6),
                "a rebuilt catalogue leaves the ghost and its marks standing");

        // Some of the rest arrives: the marks of the slots it now fills go, and the others stay.
        singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 3");
        ClientWait.until(
                context,
                "the marks of the three slots the new iron fills gone",
                client -> panel.ghostMissing().equals(Set.of(6, 7, 8)));
        require(
                context.computeOnClient(
                        client -> panel.ghostShown() && panel.fillLegend().equals("SHOW IN GRID")),
                "six of nine iron is still a ghost");

        // The last of it: the switch now fills, and the ghost has done its work.
        singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 3");
        ClientWait.until(
                context,
                "the switch offering the fill and the ghost gone",
                client -> panel.fillLegend().equals("FILL GRID") && !panel.ghostShown());
        FillScenes.pressFill(context, panel);
        ClientWait.until(
                context,
                "the grid full of iron",
                client -> grid(client).stream().allMatch(slot -> slot.getItem().is(Items.IRON_INGOT)));
        require(!context.computeOnClient(client -> panel.ghostShown()), "the fill leaves no ghost");
    }

    private static void aTableRecipeOnTheInventoryGridNeedsATable(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CataloguePanel panel = FillScenes.inventoryHolding(context, singleplayer, GRID_SLOTS);
        FillScenes.showCrafting(context, panel, Items.IRON_BLOCK);
        require(
                context.computeOnClient(client -> panel.fillLegend()).equals("NEEDS TABLE"),
                "the 3 x 3 recipe on the 2 x 2 grid says it needs a table, got "
                        + context.computeOnClient(client -> panel.fillLegend()));
        FillScenes.pressFill(context, panel);
        ClientWait.until(
                context,
                "the note that a crafting table is needed",
                client -> "NEEDS A CRAFTING TABLE".equals(panel.fillStatus()));
        CatalogueScene.parkCursor(context, CatalogueScene.layout(context, panel));
        // The strip wraps to the glass, so the words are read across its lines.
        String drawn = context.computeOnClient(client -> String.join(
                " ",
                DrawnText.of(Objects.requireNonNull(client.gui.screen())).stream()
                        .map(DrawnText.Drawn::text)
                        .toList()));
        require(drawn.contains("NEEDS A CRAFTING TABLE"), "and the recipe console draws the words, it draws " + drawn);
        require(
                context.computeOnClient(client -> gridEmpty(client) && !panel.ghostShown()),
                "nothing moved and no ghost is shown");
    }

    /** The note is about the recipe the press was on: another recipe, another item's lookup, or a cleared card takes it away. */
    private static void theNoteGoesWithItsRecipe(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CataloguePanel panel = FillScenes.inventoryHolding(context, singleplayer, GRID_SLOTS);
        FillScenes.showCrafting(context, panel, Items.IRON_BLOCK);
        FillScenes.pressFill(context, panel);
        ClientWait.until(
                context,
                "the note that a crafting table is needed",
                client -> "NEEDS A CRAFTING TABLE".equals(panel.fillStatus()));
        // Another recipe: the stick's.
        FillScenes.showCrafting(context, panel, Items.STICK);
        ClientWait.until(context, "the note gone with the recipe", client -> panel.fillStatus() == null);

        FillScenes.showCrafting(context, panel, Items.IRON_BLOCK);
        FillScenes.pressFill(context, panel);
        ClientWait.until(context, "the note again", client -> panel.fillStatus() != null);
        context.runOnClient(client -> panel.state().step(panel.catalogue(), 1));
        context.runOnClient(client -> panel.state().clear());
        ClientWait.until(context, "the note gone with the lookup", client -> panel.fillStatus() == null);
    }

    /** The ghost shows one recipe: stepping to another, looking up another item or clearing the card takes it away. */
    private static void theGhostGoesWhenItsRecipeDoes(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CataloguePanel panel = FillScenes.tableHolding(context, singleplayer, 3);
        FillScenes.showCrafting(context, panel, Items.IRON_BLOCK);
        FillScenes.pressFill(context, panel);
        ClientWait.until(context, "the ghost", client -> panel.ghostShown());
        FillScenes.showCrafting(context, panel, Items.STICK);
        ClientWait.until(context, "the ghost gone with another item's recipe", client -> !panel.ghostShown());

        // Stepping: the same item's next recipe is another recipe. The iron ingot has several (from a block, from
        // nuggets, smelted); the stick has one, as the world has no bamboo (ADR 0093). Three iron fill neither crafting
        // recipe of the ingot, so its first one shows a ghost.
        FillScenes.showCrafting(context, panel, Items.IRON_BLOCK);
        FillScenes.pressFill(context, panel);
        ClientWait.until(context, "the ghost again", client -> panel.ghostShown());
        FillScenes.showCrafting(context, panel, Items.IRON_INGOT);
        FillScenes.pressFill(context, panel);
        ClientWait.until(context, "the iron ingot's ghost", client -> panel.ghostShown());
        context.runOnClient(client -> panel.state().step(panel.catalogue(), 1));
        ClientWait.until(context, "the ghost gone with a step to the next recipe", client -> !panel.ghostShown());

        FillScenes.showCrafting(context, panel, Items.IRON_BLOCK);
        FillScenes.pressFill(context, panel);
        ClientWait.until(context, "the ghost once more", client -> panel.ghostShown());
        context.runOnClient(client -> panel.state().clear());
        ClientWait.until(context, "the ghost gone with the card", client -> !panel.ghostShown());
    }

    /** Putting the panels away, with the key or the state the button flips, takes the ghost away and it does not come back with them. */
    private static void theGhostGoesWhenThePanelsArePutAway(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CataloguePanel panel = FillScenes.tableHolding(context, singleplayer, 3);
        FillScenes.showCrafting(context, panel, Items.IRON_BLOCK);
        FillScenes.pressFill(context, panel);
        ClientWait.until(context, "the ghost", client -> panel.ghostShown());
        CatalogueScene.parkCursor(context, CatalogueScene.layout(context, panel));
        RealInput.key(context, InputConstants.KEY_O, 0);
        ClientWait.until(context, "the panels put away", client -> !panel.panelsShown());
        require(!context.computeOnClient(client -> panel.ghostShown()), "putting the panels away takes the ghost away");
        RealInput.key(context, InputConstants.KEY_O, 0);
        ClientWait.until(context, "the panels back", client -> panel.panelsShown());
        context.waitTicks(2);
        require(!context.computeOnClient(client -> panel.ghostShown()), "and it does not come back with them");

        // The button's route: the state flips and the panel is not told, so the next tick must notice.
        FillScenes.pressFill(context, panel);
        ClientWait.until(context, "the ghost", client -> panel.ghostShown());
        context.runOnClient(client -> panel.state().toggleHidden());
        ClientWait.until(context, "the ghost gone with the hidden panels", client -> !panel.ghostShown());
        context.runOnClient(client -> panel.state().toggleHidden());
    }

    private static void aFurnaceRecipeFillsTheFurnace(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CataloguePanel panel = FillScenes.furnaceHolding(context, singleplayer, 0);
        FillScenes.show(context, panel, Items.IRON_INGOT, RecipeBookType.FURNACE);
        Item ingredient = firstIngredient(context, panel);
        giveItems(singleplayer, ingredient, 3);
        ClientWait.until(
                context,
                "the switch offering the fill",
                client -> panel.fillLegend().equals("FILL GRID"));
        FillScenes.pressFill(context, panel);
        ClientWait.until(
                context,
                "the ingredient in the furnace's input slot",
                client -> furnaceInput(client).is(ingredient));
        require(
                context.computeOnClient(client -> count(
                                ClientSession.player(client).getInventory().getNonEquipmentItems(), ingredient))
                        == 2,
                "one of the three left the inventory for the furnace");
        singleplayer.getServer().runCommand("clear @p");
    }

    private static void aFurnaceRecipeIsNotHereAtTheTable(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CataloguePanel panel = FillScenes.tableHolding(context, singleplayer, 3);
        FillScenes.show(context, panel, Items.IRON_INGOT, RecipeBookType.FURNACE);
        require(
                context.computeOnClient(client -> panel.fillLegend()).equals("NOT HERE"),
                "a smelting recipe at the crafting table reads NOT HERE, got "
                        + context.computeOnClient(client -> panel.fillLegend()));
        FillScenes.pressFill(context, panel);
        ClientWait.until(
                context,
                "the note that this screen does not make it",
                client -> "NOT MADE ON THIS SCREEN".equals(panel.fillStatus()));
        context.waitTicks(3);
        require(
                context.computeOnClient(
                        client -> gridEmpty(client) && result(client).isEmpty() && !panel.ghostShown()),
                "a press moves nothing and shows no ghost");
        require(
                context.computeOnClient(client -> count(
                                ClientSession.player(client).getInventory().getNonEquipmentItems(), Items.IRON_INGOT))
                        == 3,
                "the iron stays in the inventory");
    }

    /**
     * The ghost of a partly held shaped recipe stands where the server then puts the items. With nothing held every cell is marked
     * missing, so the marks are the ghost's slots; they are compared with the slots the server fills once the player holds it all.
     */
    private static void aPartialShapedRecipeIsGhostedWhereTheServerPlacesIt(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        // The pickaxe: three iron across the top and two sticks down the middle of the 3 x 3 grid.
        CataloguePanel panel = FillScenes.tableHolding(context, singleplayer, 3);
        FillScenes.showCrafting(context, panel, Items.IRON_PICKAXE);
        FillScenes.pressFill(context, panel);
        ClientWait.until(context, "the pickaxe's ghost", client -> panel.ghostShown());
        require(
                context.computeOnClient(client -> panel.ghostMissing()).equals(Set.of(4, 7)),
                "holding the iron, the two stick slots are missing, got "
                        + context.computeOnClient(client -> panel.ghostMissing()));
        singleplayer.getServer().runCommand("clear @p");
        ClientWait.until(
                context,
                "every slot of the ghost marked",
                client -> panel.ghostMissing().size() == 5);
        Set<Integer> ghost = context.computeOnClient(client -> new HashSet<>(panel.ghostMissing()));
        singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 3");
        singleplayer.getServer().runCommand("give @p minecraft:stick 2");
        ClientWait.until(
                context,
                "the switch offering the fill",
                client -> panel.fillLegend().equals("FILL GRID"));
        FillScenes.pressFill(context, panel);
        ClientWait.until(
                context, "the pickaxe in the result", client -> result(client).is(Items.IRON_PICKAXE));
        require(
                context.computeOnClient(client -> filledSlots(client)).equals(ghost),
                "the server filled " + context.computeOnClient(client -> filledSlots(client)) + " and the ghost marked "
                        + ghost);

        // The stick: one ingredient over another, centred in the 3 x 3 grid.
        CataloguePanel table = FillScenes.tableHolding(context, singleplayer, 0);
        FillScenes.showCrafting(context, table, Items.STICK);
        FillScenes.pressFill(context, table);
        ClientWait.until(context, "the stick's ghost", client -> table.ghostShown());
        Set<Integer> stickGhost = context.computeOnClient(client -> new HashSet<>(table.ghostMissing()));
        require(stickGhost.size() == 2, "the stick has two cells, the ghost marks " + stickGhost);
        giveItems(singleplayer, firstIngredient(context, table), 2);
        ClientWait.until(
                context,
                "the switch offering the fill",
                client -> table.fillLegend().equals("FILL GRID"));
        FillScenes.pressFill(context, table);
        ClientWait.until(
                context, "the sticks in the result", client -> result(client).is(Items.STICK));
        require(
                context.computeOnClient(client -> filledSlots(client)).equals(stickGhost),
                "the server filled " + context.computeOnClient(client -> filledSlots(client))
                        + " and the stick's ghost marked " + stickGhost);
        singleplayer.getServer().runCommand("clear @p");
    }

    private static void aShiftPressFillsTheMost(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        // Enough iron for two blocks: a plain press puts one in each slot, a shift-press as many as the iron
        // goes round.
        CataloguePanel panel = FillScenes.tableHolding(context, singleplayer, 2 * GRID_SLOTS);
        FillScenes.showCrafting(context, panel, Items.IRON_BLOCK);
        FillScenes.pressFill(context, panel, InputConstants.MOD_SHIFT);
        ClientWait.until(
                context,
                "iron in every slot of the table",
                client -> grid(client).stream().allMatch(slot -> slot.getItem().is(Items.IRON_INGOT)));
        context.waitTicks(3);
        require(
                context.computeOnClient(client ->
                        grid(client).stream().allMatch(slot -> slot.getItem().getCount() == 2)),
                "a shift-press put two iron in each of the nine slots, got "
                        + context.computeOnClient(client -> grid(client).stream()
                                .map(slot -> slot.getItem().getCount())
                                .toList()));
        require(
                context.computeOnClient(client -> count(
                                ClientSession.player(client).getInventory().getNonEquipmentItems(), Items.IRON_INGOT))
                        == 0,
                "and all eighteen iron left the inventory");
    }

    /** The server refuses to clear a grid it has no room for: the press changes nothing, and the card says so. */
    private static void aRefusedFillSaysSo(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runCommand("gamemode survival @p");
        CataloguePanel panel = FillScenes.inventoryHolding(context, singleplayer, 1);
        // Every other slot of the inventory holds a full stack of stone, and the grid holds dirt that has nowhere
        // to go.
        for (int slot = 1; slot < 36; slot++) {
            singleplayer
                    .getServer()
                    .runCommand("item replace entity @p container." + slot + " with minecraft:stone 64");
        }
        singleplayer.getServer().runOnServer(server -> {
            InventoryMenu menu = server.getPlayerList().getPlayers().getFirst().inventoryMenu;
            menu.getInputGridSlots().get(1).set(new ItemStack(Items.DIRT));
            menu.broadcastFullState();
        });
        ClientWait.until(
                context,
                "the dirt in the grid",
                client -> grid(client).stream().anyMatch(slot -> slot.getItem().is(Items.DIRT)));
        FillScenes.showCrafting(context, panel, Items.IRON_NUGGET);
        ClientWait.until(
                context,
                "the switch offering the fill",
                client -> panel.fillLegend().equals("FILL GRID"));
        FillScenes.pressFill(context, panel);
        ClientWait.until(
                context,
                "the note that the grid was not filled",
                client -> "COULDN'T FILL THE GRID".equals(panel.fillStatus()));
        require(
                context.computeOnClient(client -> grid(client).stream()
                        .filter(slot -> !slot.getItem().isEmpty())
                        .allMatch(slot -> slot.getItem().is(Items.DIRT))),
                "the grid still holds only the dirt");
        singleplayer.getServer().runCommand("clear @p");
    }

    /** The indexes of the grid's slots that hold something. */
    private static Set<Integer> filledSlots(Minecraft client) {
        Set<Integer> filled = new HashSet<>();
        List<Slot> slots = grid(client);
        for (int i = 0; i < slots.size(); i++) {
            if (!slots.get(i).getItem().isEmpty()) {
                filled.add(i);
            }
        }
        return filled;
    }

    private static ItemStack furnaceInput(Minecraft client) {
        return ((AbstractContainerScreen<?>) Objects.requireNonNull(client.gui.screen()))
                .getMenu()
                .slots
                .getFirst()
                .getItem();
    }

    /** The first item that may sit in the first cell of the recipe shown: an ingredient the player can be given. */
    private static Item firstIngredient(ClientGameTestContext context, CataloguePanel panel) {
        return context.computeOnClient(client -> {
            KnownRecipe recipe =
                    Objects.requireNonNull(panel.state().shownRecipe(panel.catalogue()), "<the card shows a recipe>");
            return recipe.cells().stream()
                    .filter(cell -> !cell.isEmpty())
                    .findFirst()
                    .orElseThrow()
                    .getFirst()
                    .getItem();
        });
    }

    private static void giveItems(TestSingleplayerContext singleplayer, Item item, int count) {
        singleplayer.getServer().runCommand("give @p " + BuiltInRegistries.ITEM.getKey(item) + " " + count);
    }
}
