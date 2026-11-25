package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
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
import io.github.pkeppeler.deepcharter.test.support.ClientCursor;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
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
            ClientWait.until(context, "the recipes the server taught from the iron and the drill head", client -> {
                PartsCatalogue taught = PartsCatalogue.of(
                        ClientSession.player(client).getRecipeBook(),
                        SlotDisplayContext.fromLevel(Objects.requireNonNull(client.level)));
                return !taught.usesOf(Items.IRON_INGOT).isEmpty()
                        && !taught.recipesFor(DRILL).isEmpty();
            });
            context.runOnClient(client -> {
                CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook());
                CatalogueScreens.resetState();
            });
            onTheInventoryEverySlotLooksUp(context);
            onAChestTheConsoleOpensOnItsOwnScreen(context, singleplayer);
            anUnknownItemOpensNothing(context, singleplayer);
            closingTheConsoleKeepsTheCarriedStack(context, singleplayer);
            aHiddenCatalogueLooksNothingUp(context, singleplayer);
            rTypedInTheCreativeSearchStaysInTheBox(context, singleplayer);
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
        // The note is about the item it was asked of: it goes once the cursor leaves that slot, and does not come back.
        String noUse = Component.translatable("deepcharter.catalogue.none.uses").getString();
        Rect next = CatalogueScene.slotCell(
                context, chest, candidate -> !(candidate.container instanceof Inventory) && candidate.index == 1);
        CatalogueScene.hover(context, next);
        require(
                !texts(context, chest).contains(noUse),
                "the note goes once the cursor is on another slot, drew " + texts(context, chest));
        CatalogueScene.hover(context, slot);
        require(
                !texts(context, chest).contains(noUse),
                "and it does not come back over the unknown item, drew " + texts(context, chest));
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

    /**
     * With a stack on the cursor, a click off the console and a click on its title each bring the chest back, and the stack stays on the
     * cursor: neither the press nor its release reaches the chest, which would throw the stack out or put it in a slot.
     */
    private static void closingTheConsoleKeepsTheCarriedStack(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        ContainerScreen chest = CatalogueScene.openChest(
                context, singleplayer, List.of(new ItemStack(DRILL), new ItemStack(Items.IRON_INGOT)));
        Rect drill = CatalogueScene.slotCell(
                context, chest, slot -> !(slot.container instanceof Inventory) && slot.index == 0);
        Rect iron = CatalogueScene.slotCell(
                context, chest, slot -> !(slot.container instanceof Inventory) && slot.index == 1);
        CatalogueScene.hover(context, drill);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        ClientWait.until(
                context,
                "the drill head on the cursor",
                client -> chest.getMenu().getCarried().is(DRILL));
        int dropsBefore = drops(singleplayer);
        try {
            for (String where : List.of("off the console", "on its title")) {
                CatalogueScene.hover(context, iron);
                RealInput.key(context, InputConstants.KEY_R, 0);
                RecipeConsoleScreen console = console(context, "R over the iron with the drill head carried");
                CatalogueLayout.Console layout = CatalogueScene.centredConsole(context, console);
                if (where.equals("off the console")) {
                    // Halfway from the screen's corner to the console's.
                    ClientCursor.moveTo(
                            context, layout.casing().x() / 2.0, layout.casing().y() / 2.0);
                } else {
                    int lineHeight = context.computeOnClient(client -> client.font.lineHeight);
                    ClientCursor.moveTo(
                            context, layout.card().left() + 2, layout.card().titleY() + lineHeight / 2.0);
                }
                RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
                ClientWait.until(
                        context, "the chest back after a click " + where, client -> client.gui.screen() == chest);
                context.waitTicks(
                        5); // tick-wait: proving an absence; a stack thrown or placed reaches the server in a tick or
                // two
                ItemStack carried = context.computeOnClient(
                        client -> chest.getMenu().getCarried().copy());
                require(
                        carried.is(DRILL) && carried.getCount() == 1,
                        "a click " + where + " leaves the drill head on the cursor, got " + carried);
                require(
                        context.computeOnClient(
                                client -> !chest.getMenu().getSlot(0).hasItem()
                                        && chest.getMenu().getSlot(1).getItem().is(Items.IRON_INGOT)),
                        "a click " + where + " put nothing in the chest's slots");
                require(
                        drops(singleplayer) == dropsBefore,
                        "a click " + where + " threw nothing on the ground: " + drops(singleplayer) + " drops, "
                                + dropsBefore + " before");
            }
        } finally {
            // The drill head back in its slot, so the chest does not close with it on the cursor.
            CatalogueScene.hover(context, drill);
            RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        }
        context.setScreen(() -> null);
    }

    /** The item drops around the player on the server. */
    private static int drops(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            return player.level()
                    .getEntitiesOfClass(
                            ItemEntity.class, player.getBoundingBox().inflate(32))
                    .size();
        });
    }

    /** While the catalogue is hidden (its key), R and U over a chest slot do nothing, as they do over the inventory's slots (#1282). */
    private static void aHiddenCatalogueLooksNothingUp(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CatalogueScene.openInventory(context);
        CataloguePanel panel = CatalogueScene.panel(context);
        CatalogueState.@Nullable Lookup before = lookup(panel, context);
        context.runOnClient(client -> panel.state().toggleHidden());
        try {
            require(context.computeOnClient(client -> panel.state().hidden()), "the catalogue is hidden");
            ContainerScreen chest = CatalogueScene.openChest(context, singleplayer, List.of(new ItemStack(DRILL)));
            CatalogueScene.hover(
                    context,
                    CatalogueScene.slotCell(
                            context, chest, slot -> !(slot.container instanceof Inventory) && slot.index == 0));
            RealInput.key(context, InputConstants.KEY_R, 0);
            RealInput.key(context, InputConstants.KEY_U, 0);
            require(
                    context.computeOnClient(client -> client.gui.screen()) == chest,
                    "R and U over a chest slot open nothing while the catalogue is hidden");
            require(
                    Objects.equals(lookup(panel, context), before),
                    "and look nothing up, got " + lookup(panel, context));
        } finally {
            context.runOnClient(client -> panel.state().toggleHidden());
        }
        context.setScreen(() -> null);
    }

    /** In the creative inventory's search tab the search box has the keyboard: an R typed over an item is a letter of the search. */
    private static void rTypedInTheCreativeSearchStaysInTheBox(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runCommand("gamemode creative @p");
        ClientWait.until(
                context,
                "the player in creative",
                client -> ClientSession.player(client).isCreative());
        try {
            context.setScreen(() -> {
                Minecraft client = Minecraft.getInstance();
                return new CreativeModeInventoryScreen(
                        ClientSession.player(client),
                        ClientSession.connection(client).enabledFeatures(),
                        client.options.operatorItemsTab().get());
            });
            ClientWait.screen(context, CreativeModeInventoryScreen.class);
            CreativeModeInventoryScreen creative =
                    context.computeOnClient(client -> ClientSession.screen(client, CreativeModeInventoryScreen.class));
            // Outside the search, R over the iron opens its recipes: the case below is the search box's, not a dead
            // key.
            hoverIron(context, creative);
            RealInput.key(context, InputConstants.KEY_R, 0);
            console(context, "R over the creative inventory's hotbar");
            RealInput.key(context, InputConstants.KEY_ESCAPE, 0);
            ClientWait.until(context, "the creative inventory back", client -> client.gui.screen() == creative);
            // The chat key opens the search tab, its box focused.
            RealInput.key(context, InputConstants.KEY_T, 0);
            ClientWait.until(context, "the creative search tab", client -> creative.isInputCaptured());
            // The search tab lays the hotbar out where it likes: the iron's cell is found again.
            hoverIron(context, creative);
            // R as the window sends it: the key goes down, its text follows, the key comes up.
            context.runOnClient(client -> {
                long window = client.getWindow().handle();
                KeyEvent r = new KeyEvent(InputConstants.KEY_R, 0, 0);
                client.keyboardHandler.keyPress(window, InputConstants.PRESS, r);
                client.keyboardHandler.textInput(window, "r");
                client.keyboardHandler.keyPress(window, InputConstants.RELEASE, r);
            });
            context.waitTicks(1);
            require(
                    context.computeOnClient(client -> client.gui.screen()) == creative,
                    "R typed in the creative search over the hotbar's iron opens no console");
            String typed = context.computeOnClient(client -> searchText(creative));
            require("r".equals(typed), "the R is in the search box, got '" + typed + "'");
        } finally {
            context.setScreen(() -> null);
            singleplayer.getServer().runCommand("gamemode survival @p");
        }
    }

    /** Hovers the hotbar's iron on the creative inventory, and checks it is the slot the screen takes as hovered. */
    private static void hoverIron(ClientGameTestContext context, CreativeModeInventoryScreen creative) {
        CatalogueScene.hover(
                context,
                CatalogueScene.slotCell(
                        context,
                        creative,
                        slot -> slot.container instanceof Inventory && slot.getContainerSlot() == HOTBAR_SLOT));
        require(
                context.computeOnClient(client -> creative.hoveredSlot != null
                        && creative.hoveredSlot.getItem().is(Items.IRON_INGOT)),
                "the cursor is on the creative inventory's iron");
    }

    /** What the creative inventory's search box holds; the screen keeps the box private, so it is read by name. */
    private static String searchText(CreativeModeInventoryScreen screen) {
        try {
            Field box = CreativeModeInventoryScreen.class.getDeclaredField("searchBox");
            box.setAccessible(true);
            return ((EditBox) box.get(screen)).getValue();
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("the creative inventory has no field searchBox; the test reads it by name", e);
        }
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
