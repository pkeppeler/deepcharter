package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.KnownRecipe;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The scenes of the recipe card's fill switch (#1259), shared by its client test and its evidence scenario: a real inventory or a real
 * crafting table menu, opened on the server, holding a number of iron ingots, with the card showing the crafting recipe of an item the
 * iron teaches (the iron block, which needs the 3 x 3 grid, and iron nuggets, which fit the 2 x 2 one).
 */
public final class FillScenes {
    private FillScenes() {}

    public static @Nullable KnownRecipe crafting(CataloguePanel panel, Item item) {
        return recipe(panel, item, RecipeBookType.CRAFTING);
    }

    /** The first recipe of {@code item} that the {@code book} makes, or null while the catalogue knows none. */
    public static @Nullable KnownRecipe recipe(CataloguePanel panel, Item item, RecipeBookType book) {
        return panel.catalogue().recipesFor(item).stream()
                .filter(recipe -> recipe.book() == book)
                .findFirst()
                .orElse(null);
    }

    /** Opens the inventory with exactly {@code iron} iron ingots held and nothing in the grid. */
    public static CataloguePanel inventoryHolding(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, int iron) {
        context.setScreen(() -> null);
        hold(context, singleplayer, iron);
        CatalogueScene.openInventory(context);
        return CatalogueScene.panel(context);
    }

    /** Opens a crafting table's menu on the server, with exactly {@code iron} iron ingots held. */
    public static CataloguePanel tableHolding(
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

    /** Opens a furnace's menu on the server, with exactly {@code iron} iron ingots held. */
    public static CataloguePanel furnaceHolding(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, int iron) {
        context.setScreen(() -> null);
        hold(context, singleplayer, iron);
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.openMenu(new SimpleMenuProvider(
                    (id, inventory, who) -> new FurnaceMenu(id, inventory), Component.literal("Furnace")));
        });
        ClientWait.screen(context, FurnaceScreen.class);
        context.waitTicks(3);
        return CatalogueScene.panel(context);
    }

    public static void hold(ClientGameTestContext context, TestSingleplayerContext singleplayer, int iron) {
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

    public static int count(List<ItemStack> stacks, Item item) {
        return stacks.stream()
                .filter(stack -> stack.is(item))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    /** Looks {@code item} up and steps to its crafting recipe, so the card shows the one the switch fills. */
    public static void showCrafting(ClientGameTestContext context, CataloguePanel panel, Item item) {
        show(context, panel, item, RecipeBookType.CRAFTING);
    }

    /** Looks {@code item} up and steps to its first recipe of {@code book}, so the card shows the one the switch fills. */
    public static void show(ClientGameTestContext context, CataloguePanel panel, Item item, RecipeBookType book) {
        context.runOnClient(client -> {
            CatalogueState state = panel.state();
            state.look(panel.catalogue(), item, CatalogueState.Mode.RECIPES);
            KnownRecipe wanted =
                    Objects.requireNonNull(recipe(panel, item, book), () -> "a " + book + " recipe of " + item);
            for (int step = 0; step < state.found(panel.catalogue()).size(); step++) {
                if (wanted.equals(state.shownRecipe(panel.catalogue()))) {
                    break;
                }
                state.step(panel.catalogue(), 1);
            }
            require(
                    wanted.equals(state.shownRecipe(panel.catalogue())),
                    "the card shows the " + book + " recipe of " + item);
        });
        context.waitTicks(2);
    }

    public static void pressFill(ClientGameTestContext context, CataloguePanel panel) {
        pressFill(context, panel, 0);
    }

    /** Presses the switch with {@code modifiers} held, as a shift-press fills as many as it can. */
    public static void pressFill(ClientGameTestContext context, CataloguePanel panel, int modifiers) {
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.hover(context, layout.card().fill());
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT, modifiers);
    }
}
