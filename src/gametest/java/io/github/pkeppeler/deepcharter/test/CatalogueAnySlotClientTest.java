package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueScreens;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.KnownRecipe;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.PartsCatalogue;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.RecipeConsoleScreen;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.test.support.CatalogueFixtures;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest for #1266 (a): R and U over an item in any slot open its recipes or uses. On the inventory (a screen with the
 * catalogue beside it) a slot of the main inventory and of the hotbar look the item up in the catalogue's own recipe console. On a chest,
 * which has no catalogue, a chest slot and a hotbar slot open the recipe console on a screen of its own, and closing it comes back to
 * the same chest, still open on the server. An item the charter knows nothing of opens nothing and changes nothing: a note over the cursor,
 * clear of the item's tooltip, says so, and no recipe of it is drawn.
 */
public class CatalogueAnySlotClientTest implements FabricClientGameTest {
    private static final Item DRILL = HangarParts.DRILL_HEAD;
    /** Nothing the fixtures know makes or takes it. */
    private static final Item UNKNOWN = Items.DIAMOND_SWORD;
    /** The last slot of the main inventory, clear of what a new player is given. */
    private static final int MAIN_INVENTORY_SLOT = Inventory.INVENTORY_SIZE - 1;
    /** The last slot of the hotbar. */
    private static final int HOTBAR_SLOT = Inventory.getSelectionSize() - 1;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player's inventory", client -> client.player != null && client.level != null);
            // The iron in the hotbar, the drill head in the main inventory: what a slot of each holds. Slots of their
            // own, clear of
            // the handbook every player is given on joining.
            singleplayer
                    .getServer()
                    .runCommand("item replace entity @p container." + HOTBAR_SLOT + " with minecraft:iron_ingot");
            singleplayer
                    .getServer()
                    .runCommand("item replace entity @p container." + MAIN_INVENTORY_SLOT + " with "
                            + BuiltInRegistries.ITEM.getKey(DRILL));
            ClientWait.until(
                    context,
                    "the iron in the hotbar and the drill head in the main inventory",
                    client -> ClientSession.player(client)
                                    .getInventory()
                                    .getItem(HOTBAR_SLOT)
                                    .is(Items.IRON_INGOT)
                            && ClientSession.player(client)
                                    .getInventory()
                                    .getItem(MAIN_INVENTORY_SLOT)
                                    .is(DRILL));
            // What the server taught from what the player holds is put back to the fixtures, so the unknown item stays
            // unknown.
            context.waitTicks(5);
            context.runOnClient(client -> {
                CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook());
                CatalogueScreens.resetState();
            });
            onTheInventoryEverySlotLooksUp(context);
            onAChestTheConsoleOpensOnItsOwnScreen(context, singleplayer);
            anUnknownItemOpensNothing(context, singleplayer);
            context.setScreen(() -> null);
        }
    }

    private static CatalogueState.@Nullable Lookup lookup(CataloguePanel panel, ClientGameTestContext context) {
        return context.computeOnClient(client -> panel.state().lookup());
    }

    private static void onTheInventoryEverySlotLooksUp(ClientGameTestContext context) {
        CatalogueScene.openInventory(context);
        CataloguePanel panel = CatalogueScene.panel(context);
        AbstractContainerScreen<?> screen = context.computeOnClient(
                client -> (AbstractContainerScreen<?>) Objects.requireNonNull(client.gui.screen()));
        Rect main = CatalogueScene.slotCell(
                context,
                screen,
                slot -> slot.container instanceof Inventory && slot.getContainerSlot() == MAIN_INVENTORY_SLOT);
        CatalogueScene.hover(context, main);
        RealInput.key(context, InputConstants.KEY_U, 0);
        CatalogueState.Lookup uses = lookup(panel, context);
        require(
                uses != null && uses.item() == DRILL && uses.mode() == CatalogueState.Mode.USES,
                "U on a slot of the main inventory shows the uses of its item, got " + uses);
        Rect hotbar = CatalogueScene.slotCell(
                context, screen, slot -> slot.container instanceof Inventory && slot.getContainerSlot() == HOTBAR_SLOT);
        CatalogueScene.hover(context, hotbar);
        RealInput.key(context, InputConstants.KEY_R, 0);
        CatalogueState.Lookup recipes = lookup(panel, context);
        require(
                recipes != null && recipes.item() == Items.IRON_INGOT && recipes.mode() == CatalogueState.Mode.RECIPES,
                "R on a hotbar slot shows the recipes of its item, got " + recipes);
        require(
                context.computeOnClient(client -> client.gui.screen()) == screen,
                "on the inventory the catalogue's own console shows it: the screen stays");
        context.setScreen(() -> null);
    }

    private static void onAChestTheConsoleOpensOnItsOwnScreen(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        ContainerScreen chest = CatalogueScene.openChest(context, singleplayer, List.of(new ItemStack(DRILL)));
        require(
                context.computeOnClient(client -> CatalogueScreens.panelOf(chest)) == null,
                "a chest has no catalogue beside it, so this is the case the issue is about");

        Rect chestSlot = CatalogueScene.slotCell(
                context, chest, slot -> !(slot.container instanceof Inventory) && slot.index == 0);
        CatalogueScene.hover(context, chestSlot);
        RealInput.key(context, InputConstants.KEY_R, 0);
        RecipeConsoleScreen console = console(context, "R on a chest slot");
        requireShows(context, console, DRILL, CatalogueState.Mode.RECIPES, "R on a chest slot");

        RealInput.key(context, InputConstants.KEY_ESCAPE, 0);
        ClientWait.until(context, "the chest back", client -> client.gui.screen() == chest);
        require(
                singleplayer
                        .getServer()
                        .computeOnServer(server ->
                                server.getPlayerList().getPlayers().getFirst().containerMenu instanceof ChestMenu),
                "the chest is still open on the server after the console closed");
        require(
                context.computeOnClient(client -> ClientSession.player(client).containerMenu == chest.getMenu()),
                "and on the client it is the same menu");

        // A hotbar slot of the chest screen, with U: the player's own items in a container screen count as well.
        Rect hotbar = CatalogueScene.slotCell(
                context, chest, slot -> slot.container instanceof Inventory && slot.getContainerSlot() == HOTBAR_SLOT);
        CatalogueScene.hover(context, hotbar);
        RealInput.key(context, InputConstants.KEY_U, 0);
        RecipeConsoleScreen uses = console(context, "U on a hotbar slot of the chest");
        requireShows(context, uses, Items.IRON_INGOT, CatalogueState.Mode.USES, "U on a hotbar slot of the chest");
        RealInput.key(context, InputConstants.KEY_E, 0);
        ClientWait.until(context, "the chest back after E", client -> client.gui.screen() == chest);
        context.setScreen(() -> null);
    }

    private static void anUnknownItemOpensNothing(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        PartsCatalogue known = context.computeOnClient(client -> PartsCatalogue.of(
                ClientSession.player(client).getRecipeBook(),
                SlotDisplayContext.fromLevel(Objects.requireNonNull(client.level))));
        require(
                known.recipesFor(UNKNOWN).isEmpty() && known.usesOf(UNKNOWN).isEmpty(),
                "the charter knows no recipe of the diamond sword: this check needs it unknown");
        ContainerScreen chest = CatalogueScene.openChest(context, singleplayer, List.of(new ItemStack(UNKNOWN)));
        Rect slot = CatalogueScene.slotCell(
                context, chest, candidate -> !(candidate.container instanceof Inventory) && candidate.index == 0);
        CatalogueScene.hover(context, slot);
        for (int key : new int[] {InputConstants.KEY_R, InputConstants.KEY_U}) {
            RealInput.key(context, key, 0);
            context.waitTicks(1);
            require(
                    context.computeOnClient(client -> client.gui.screen()) == chest,
                    "R or U on an item with nothing known opens nothing: the chest stays");
            List<String> drawn = texts(context, chest);
            String none = Component.translatable(
                            key == InputConstants.KEY_R
                                    ? "deepcharter.catalogue.none.recipes"
                                    : "deepcharter.catalogue.none.uses")
                    .getString();
            require(drawn.contains(none), "a note by the cursor says '" + none + "', drew " + drawn);
            requireClearOfTheTooltip(context, chest, none);
            require(!drawn.contains("DIAMOND SWORD"), "nothing is titled with the unknown item, drew " + drawn);
        }
        context.setScreen(() -> null);
        // The catalogue was left on the iron's uses by the chest's console: the unknown item took its place nowhere.
        CatalogueScene.openInventory(context);
        CataloguePanel panel = CatalogueScene.panel(context);
        CatalogueState.Lookup last = lookup(panel, context);
        require(
                last != null && last.item() == Items.IRON_INGOT && last.mode() == CatalogueState.Mode.USES,
                "R and U on an unknown item look nothing up, so the last lookup stands, got " + last);
        context.runOnClient(client -> panel.state().back());
        CatalogueState.Lookup before = lookup(panel, context);
        require(
                before != null && before.item() == DRILL,
                "and nothing of the unknown item reached the history, got " + before);
        context.setScreen(() -> null);
    }

    private static RecipeConsoleScreen console(ClientGameTestContext context, String what) {
        ClientWait.until(
                context,
                "the recipe console after " + what,
                client -> client.gui.screen() instanceof RecipeConsoleScreen);
        return context.computeOnClient(client -> (RecipeConsoleScreen) Objects.requireNonNull(client.gui.screen()));
    }

    /**
     * The console shows what the player asked for: titled with what the first recipe found makes, and its counter in the mode asked (R or
     * U) over every recipe the charter knows now (the server may have taught more than the fixtures by then).
     */
    private static void requireShows(
            ClientGameTestContext context,
            RecipeConsoleScreen console,
            Item item,
            CatalogueState.Mode mode,
            String what) {
        List<KnownRecipe> found = context.computeOnClient(client -> {
            PartsCatalogue known = PartsCatalogue.of(
                    ClientSession.player(client).getRecipeBook(),
                    SlotDisplayContext.fromLevel(Objects.requireNonNull(client.level)));
            return mode == CatalogueState.Mode.RECIPES ? known.recipesFor(item) : known.usesOf(item);
        });
        require(
                found.size() > 1,
                what + ": the charter knows more than one, so the counter is drawn, got " + found.size());
        String title = context.computeOnClient(client ->
                found.getFirst().results().getFirst().getHoverName().getString().toUpperCase(Locale.ROOT));
        String counter = (mode == CatalogueState.Mode.RECIPES ? "R" : "U") + " 1/" + found.size();
        List<String> drawn = texts(context, console);
        // A long title takes two lines, drawn as two strings.
        require(
                String.join(" ", drawn).contains(title) && drawn.contains(counter),
                what + " shows '" + title + "' at '" + counter + "', drew " + drawn);
    }

    /**
     * The note {@code text} sits above where the hovered item's tooltip starts, its frame {@link TooltipRenderUtil#MOUSE_OFFSET} and
     * {@link TooltipRenderUtil#PADDING_TOP} above the cursor, so the tooltip, which the game draws over everything, does not cover it.
     */
    private static void requireClearOfTheTooltip(ClientGameTestContext context, Screen screen, String text) {
        context.runOnClient(client -> {
            var window = client.getWindow();
            int mouseX = (int) client.mouseHandler.getScaledXPos(window);
            int mouseY = (int) client.mouseHandler.getScaledYPos(window);
            DrawnText.Drawn note = DrawnText.of(screen, mouseX, mouseY).stream()
                    .filter(drawn -> drawn.text().equals(text))
                    .findFirst()
                    .orElseThrow();
            int tooltipTop = mouseY - TooltipRenderUtil.MOUSE_OFFSET - TooltipRenderUtil.PADDING_TOP;
            require(
                    note.box().bottom() <= tooltipTop,
                    "the note '" + text + "' ends at y " + note.box().bottom() + ", above the item's tooltip at y "
                            + tooltipTop + ", so the tooltip does not cover it");
        });
    }

    private static List<String> texts(ClientGameTestContext context, Screen screen) {
        return context.computeOnClient(client -> {
            var window = client.getWindow();
            return DrawnText.of(screen, (int) client.mouseHandler.getScaledXPos(window), (int)
                            client.mouseHandler.getScaledYPos(window))
                    .stream()
                    .map(DrawnText.Drawn::text)
                    .toList();
        });
    }
}
