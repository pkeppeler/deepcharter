package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.KnownRecipe;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

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
            CatalogueScene.openInventory(context);
            CataloguePanel panel = CatalogueScene.panel(context);
            ClientWait.until(
                    context,
                    "the recipes the iron teaches",
                    client -> crafting(panel, Items.IRON_BLOCK) != null && crafting(panel, Items.IRON_NUGGET) != null);
            context.setScreen(() -> null);

            aFullHandFillsTheInventoryGrid(context, singleplayer);
            aFullHandFillsTheTable(context, singleplayer);
            aShortHandShowsAGhostAndMovesNothing(context, singleplayer);
            aTableRecipeOnTheInventoryGridNeedsATable(context, singleplayer);
            context.setScreen(() -> null);
            singleplayer.getServer().runCommand("clear @p");
        }
    }

    // ---------------------------------------------------------------- the scenes

    private static @Nullable KnownRecipe crafting(CataloguePanel panel, Item item) {
        return panel.catalogue().recipesFor(item).stream()
                .filter(recipe -> recipe.book() == RecipeBookType.CRAFTING)
                .findFirst()
                .orElse(null);
    }

    /** Opens the inventory with exactly {@code iron} iron ingots held and nothing in the grid. */
    private static CataloguePanel inventoryHolding(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, int iron) {
        context.setScreen(() -> null);
        hold(context, singleplayer, iron);
        CatalogueScene.openInventory(context);
        return CatalogueScene.panel(context);
    }

    /** Opens a crafting table's menu on the server, with exactly {@code iron} iron ingots held. */
    private static CataloguePanel tableHolding(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, int iron) {
        context.setScreen(() -> null);
        hold(context, singleplayer, iron);
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.openMenu(new SimpleMenuProvider(
                    (id, inventory, who) -> new CraftingMenu(id, inventory), Component.literal("Crafting")));
        });
        ClientWait.screen(context, CraftingScreen.class);
        context.waitTicks(3);
        return CatalogueScene.panel(context);
    }

    private static void hold(ClientGameTestContext context, TestSingleplayerContext singleplayer, int iron) {
        singleplayer.getServer().runCommand("clear @p");
        if (iron > 0) {
            singleplayer.getServer().runCommand("give @p minecraft:iron_ingot " + iron);
        }
        ClientWait.until(
                context,
                iron + " iron in the inventory",
                client -> count(ClientSession.player(client).getInventory().getNonEquipmentItems(), Items.IRON_INGOT)
                        == iron);
    }

    private static int count(List<ItemStack> stacks, Item item) {
        return stacks.stream()
                .filter(stack -> stack.is(item))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    /** Looks {@code item} up and steps to its crafting recipe, so the card shows the one the switch fills. */
    private static void showCrafting(ClientGameTestContext context, CataloguePanel panel, Item item) {
        context.runOnClient(client -> {
            CatalogueState state = panel.state();
            state.look(panel.catalogue(), item, CatalogueState.Mode.RECIPES);
            KnownRecipe wanted = Objects.requireNonNull(crafting(panel, item), () -> "a crafting recipe of " + item);
            for (int step = 0; step < state.found(panel.catalogue()).size(); step++) {
                if (wanted.equals(state.shownRecipe(panel.catalogue()))) {
                    break;
                }
                state.step(panel.catalogue(), 1);
            }
            require(
                    wanted.equals(state.shownRecipe(panel.catalogue())),
                    "the card shows the crafting recipe of " + item);
        });
        context.waitTicks(2);
    }

    private static void pressFill(ClientGameTestContext context, CataloguePanel panel) {
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.hover(context, layout.card().fill());
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
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
        CataloguePanel panel = inventoryHolding(context, singleplayer, GRID_SLOTS);
        showCrafting(context, panel, Items.IRON_NUGGET);
        require(
                context.computeOnClient(client -> panel.fillLegend()).equals("FILL GRID"),
                "holding the iron, the switch offers to fill, got "
                        + context.computeOnClient(client -> panel.fillLegend()));
        pressFill(context, panel);
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
        CataloguePanel panel = tableHolding(context, singleplayer, GRID_SLOTS);
        showCrafting(context, panel, Items.IRON_BLOCK);
        require(
                context.computeOnClient(client -> panel.fillLegend()).equals("FILL GRID"),
                "holding nine iron at the table, the switch offers to fill");
        pressFill(context, panel);
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
        CataloguePanel panel = tableHolding(context, singleplayer, 3);
        showCrafting(context, panel, Items.IRON_BLOCK);
        require(
                context.computeOnClient(client -> panel.fillLegend()).equals("SHOW IN GRID"),
                "holding three of nine iron, the switch offers the ghost, got "
                        + context.computeOnClient(client -> panel.fillLegend()));
        pressFill(context, panel);
        context.waitTicks(5);
        require(context.computeOnClient(client -> panel.ghostShown()), "a press shows the recipe as a ghost");
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

        // The rest of the iron arrives: the ghost is the recipe still, the switch now fills it, and a fill takes the
        // ghost away.
        singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 6");
        ClientWait.until(
                context,
                "the switch offering the fill",
                client -> panel.fillLegend().equals("FILL GRID"));
        pressFill(context, panel);
        ClientWait.until(
                context,
                "the grid full of iron",
                client -> grid(client).stream().allMatch(slot -> slot.getItem().is(Items.IRON_INGOT)));
        require(!context.computeOnClient(client -> panel.ghostShown()), "the fill took the ghost away");
    }

    private static void aTableRecipeOnTheInventoryGridNeedsATable(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CataloguePanel panel = inventoryHolding(context, singleplayer, GRID_SLOTS);
        showCrafting(context, panel, Items.IRON_BLOCK);
        require(
                context.computeOnClient(client -> panel.fillLegend()).equals("NEEDS TABLE"),
                "the 3 x 3 recipe on the 2 x 2 grid says it needs a table, got "
                        + context.computeOnClient(client -> panel.fillLegend()));
        pressFill(context, panel);
        context.waitTicks(5);
        require(
                "NEEDS A CRAFTING TABLE".equals(context.computeOnClient(client -> panel.fillStatus())),
                "a press says it needs a crafting table, got " + context.computeOnClient(client -> panel.fillStatus()));
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
}
