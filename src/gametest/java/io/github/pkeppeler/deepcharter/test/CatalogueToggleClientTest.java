package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueFavourites;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueKeys;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueScreens;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueToggleButton;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetOpener;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.test.support.CatalogueFixtures;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ContainerScreenFields;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.sdl.SDLKeyboard;

/**
 * Client GameTest for #1282, the parts catalogue's show/hide button: it stands in the recipe-book button's place on the inventory and the
 * crafting table, clear of every slot and inside the screen at each window size; a click and the key (O unless rebound) put the panels
 * away and bring them back, but not while the search has the keyboard; its tooltip names the key bound now; and the choice carries from
 * one screen to the next.
 */
public class CatalogueToggleClientTest implements FabricClientGameTest {
    private static final Item DRILL = HangarParts.DRILL_HEAD;

    /** The game's left button: what a vanilla button and the catalogue's panels answer to. */
    private static final int LEFT_MOUSE = InputConstants.MOUSE_BUTTON_LEFT;
    /** The default window, 854 by 480 at GUI scale 2. */
    private static final int WIDTH = 427;

    private static final int HEIGHT = 240;
    /** The narrowest GUI the panels draw at. */
    private static final int NARROWEST = 425;
    /** An item icon, and so a slot's hit area. */
    private static final int ICON = 16;
    /** Pages assumed for turning the catalogue's page: more than the page the test leaves it on. */
    private static final int PAGES = 99;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Objects.requireNonNull(singleplayer.getServer(), "a world is running");
            ClientWait.until(
                    context, "the player's inventory", client -> client.player != null && client.level != null);
            context.runOnClient(client ->
                    CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook()));
            CatalogueScene.openInventory(context);
            theButtonStandsClearAtEveryWindowSize(context, "the inventory");
            aClickPutsThePanelsAwayAndBringsThemBack(context);
            theTooltipNamesTheKeyBoundNow(context);
            theKeyTogglesButNotWhileSearching(context);
            theChoiceIsRemembered(context);
            theHiddenPanelsSwallowNothing(context, singleplayer);
            lookingAnItemUpBringsHiddenPanelsBack(context);
            aSpecSheetWorksWhileHidden(context);
            theNarrowFallbackKeepsVanillasBookButton(context);
            theFurnaceHasTheButtonToo(context);
            context.setScreen(() -> null);
        }
    }

    private static Screen shown(Minecraft client) {
        return Objects.requireNonNull(client.gui.screen(), "a screen is open");
    }

    private static CatalogueToggleButton button(Screen screen) {
        List<CatalogueToggleButton> found = Screens.getWidgets(screen).stream()
                .filter(CatalogueToggleButton.class::isInstance)
                .map(CatalogueToggleButton.class::cast)
                .toList();
        require(found.size() == 1, "the screen has one catalogue button, found " + found.size());
        return found.getFirst();
    }

    private static CatalogueToggleButton button(ClientGameTestContext context) {
        return context.computeOnClient(client -> button(shown(client)));
    }

    private static Rect rectOf(CatalogueToggleButton button) {
        return new Rect(button.getX(), button.getY(), button.getWidth(), button.getHeight());
    }

    private static boolean overlap(Rect a, Rect b) {
        return a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom();
    }

    private static boolean hidden(ClientGameTestContext context) {
        return context.computeOnClient(
                client -> CatalogueScene.panelNow(client).state().hidden());
    }

    private static void click(ClientGameTestContext context, Rect rect, int mouseButton) {
        CatalogueScene.hover(context, rect);
        context.getInput().pressMouse(mouseButton);
        context.waitTicks(1);
    }

    private static CraftingScreen craftingScreen(Minecraft client) {
        return new CraftingScreen(
                new CraftingMenu(0, ClientSession.player(client).getInventory()),
                ClientSession.player(client).getInventory(),
                Component.translatable("container.crafting"));
    }

    // ---------------------------------------------------------------- where it stands

    private static void theButtonStandsClearAtEveryWindowSize(ClientGameTestContext context, String where) {
        AbstractContainerScreen<?> screen =
                context.computeOnClient(client -> (AbstractContainerScreen<?>) shown(client));
        for (int width : new int[] {NARROWEST, WIDTH, 600}) {
            context.runOnClient(client -> screen.resize(width, HEIGHT));
            context.waitTicks(2);
            String at = where + " at " + width + " by " + HEIGHT;
            requireClear(context, screen, at);
        }
    }

    private static void requireClear(ClientGameTestContext context, AbstractContainerScreen<?> screen, String at) {
        context.runOnClient(client -> {
            CataloguePanel panel = CatalogueScene.panelNow(client);
            CatalogueLayout layout = Objects.requireNonNull(panel.layout(), "the panels are laid out " + at);
            Rect button = rectOf(button(screen));
            require(
                    button.x() >= 0
                            && button.y() >= 0
                            && button.right() <= screen.width
                            && button.bottom() <= screen.height,
                    "the button " + button + " is inside the screen " + at);
            int left = ContainerScreenFields.intField(screen, "leftPos");
            int top = ContainerScreenFields.intField(screen, "topPos");
            for (Slot slot : screen.getMenu().slots) {
                Rect box = new Rect(left + slot.x, top + slot.y, ICON, ICON);
                require(!overlap(button, box), "the button " + button + " overlaps the slot " + box + " " + at);
            }
            require(
                    !overlap(button, layout.catalogue()) && !overlap(button, layout.recipe()),
                    "the button " + button + " is clear of both panels " + at);
            require(
                    button.x() >= layout.inventory().x()
                            && button.right() <= layout.inventory().right()
                            && button.y() >= layout.inventory().y()
                            && button.bottom() <= layout.inventory().bottom(),
                    "the button " + button + " is on the inventory image " + layout.inventory() + " " + at
                            + ", so clear of the effect list beside it");
        });
    }

    // ---------------------------------------------------------------- what it does

    private static void aClickPutsThePanelsAwayAndBringsThemBack(ClientGameTestContext context) {
        context.runOnClient(client -> shown(client).resize(WIDTH, HEIGHT));
        Rect button = rectOf(button(context));
        require(!hidden(context), "the panels start shown");
        click(context, button, LEFT_MOUSE);
        require(hidden(context), "a click on the button puts the panels away");
        require(
                !context.computeOnClient(
                        client -> CatalogueScene.panelNow(client).panelsShown()),
                "and the panel reports it is not shown");
        click(context, button, LEFT_MOUSE);
        require(!hidden(context), "a second click brings them back");
        require(
                context.computeOnClient(
                        client -> CatalogueScene.panelNow(client).panelsShown()),
                "and the panel reports it is shown");
    }

    private static void theTooltipNamesTheKeyBoundNow(ClientGameTestContext context) {
        context.waitTicks(1);
        require(
                button(context).tooltipText().equals("Parts catalogue (O)"),
                "the tooltip shows the default key, got '" + button(context).tooltipText() + "'");
        rebind(context, InputConstants.KEY_P);
        try {
            context.waitTicks(2);
            require(
                    button(context).tooltipText().equals("Parts catalogue (P)"),
                    "after a rebind the tooltip shows the new key, got '"
                            + button(context).tooltipText() + "'");
            context.getInput().pressKey(InputConstants.KEY_P);
            context.waitTicks(1);
            require(hidden(context), "the rebound key toggles the panels");
            context.getInput().pressKey(InputConstants.KEY_O);
            context.waitTicks(1);
            require(hidden(context), "and the old key no longer does");
            context.getInput().pressKey(InputConstants.KEY_P);
            context.waitTicks(1);
            require(!hidden(context), "the rebound key brings them back");
        } finally {
            rebind(context, InputConstants.KEY_O);
        }
        context.waitTicks(2);
        require(
                button(context).tooltipText().equals("Parts catalogue (O)"),
                "the key is back to O, got '" + button(context).tooltipText() + "'");
    }

    private static void rebind(ClientGameTestContext context, int key) {
        context.runOnClient(client -> {
            CatalogueKeys.TOGGLE.setKey(InputConstants.Type.KEYBOARD.getOrCreate(key));
            KeyMapping.resetMapping();
        });
    }

    private static void theKeyTogglesButNotWhileSearching(ClientGameTestContext context) {
        context.getInput().pressKey(InputConstants.KEY_O);
        context.waitTicks(1);
        require(hidden(context), "O puts the panels away");
        context.getInput().pressKey(InputConstants.KEY_O);
        context.waitTicks(1);
        require(!hidden(context), "O brings them back");

        CataloguePanel panel = CatalogueScene.panel(context);
        Rect search = CatalogueScene.layout(context, panel).shelf().search();
        click(context, search, LEFT_MOUSE);
        require(context.computeOnClient(client -> panel.searchFocused()), "a click on the search field focuses it");
        RealInput.type(context, "o");
        context.waitTicks(1);
        require(!hidden(context), "an o typed in the search does not hide the catalogue");
        require(
                context.computeOnClient(client -> panel.state().query()).equals("o"),
                "the o went into the search, got '"
                        + context.computeOnClient(client -> panel.state().query()) + "'");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.runOnClient(client -> panel.state().setQuery(""));
        context.waitTicks(1);
        require(!context.computeOnClient(client -> panel.searchFocused()), "escape leaves the search");
    }

    private static void theChoiceIsRemembered(ClientGameTestContext context) {
        context.getInput().pressKey(InputConstants.KEY_O);
        context.waitTicks(1);
        require(hidden(context), "the panels are put away before the screen closes");
        context.setScreen(() -> null);
        CatalogueScene.openInventory(context);
        require(hidden(context), "a reopened inventory keeps the panels away");
        require(
                !context.computeOnClient(
                        client -> CatalogueScene.panelNow(client).panelsShown()),
                "and draws none");
        // The button is still there to bring them back.
        require(button(context).visible, "the button is shown while the panels are away");
        context.runOnClient(client -> client.gui.setScreen(craftingScreen(client)));
        ClientWait.screen(context, CraftingScreen.class);
        context.waitTicks(3);
        require(hidden(context), "the crafting table keeps them away too");
        theButtonStandsClearAtEveryWindowSize(context, "the crafting table");
        click(context, rectOf(button(context)), LEFT_MOUSE);
        require(!hidden(context), "the button on the crafting table brings them back");
        context.setScreen(() -> null);
        CatalogueScene.openInventory(context);
        require(!hidden(context), "and the inventory shows them again");
        // What leaving a world does: the next panel is as a new client's.
        context.runOnClient(client -> {
            CatalogueScene.panelNow(client).state().toggleHidden();
            CatalogueScreens.resetState();
            client.gui.setScreen(null);
            client.gui.setScreen(new InventoryScreen(ClientSession.player(client)));
        });
        require(!hidden(context), "after a reset the panels are shown");
    }

    // ---------------------------------------------------------------- hidden panels

    private static void setHidden(ClientGameTestContext context, boolean hidden) {
        context.runOnClient(client -> {
            CatalogueState state = CatalogueScene.panelNow(client).state();
            if (state.hidden() != hidden) {
                state.toggleHidden();
            }
        });
        context.waitTicks(1);
    }

    private static void theHiddenPanelsSwallowNothing(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CatalogueScene.openInventory(context);
        singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 1");
        ClientWait.until(
                context,
                "an iron ingot in the inventory",
                client -> ClientSession.player(client).getInventory().contains(new ItemStack(Items.IRON_INGOT)));
        context.waitTicks(2);
        CataloguePanel panel = CatalogueScene.panel(context);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        UUID player =
                context.computeOnClient(client -> ClientSession.player(client).getUUID());
        List<Item> starred = CatalogueFavourites.shared().items(player);
        Rect slot = context.computeOnClient(client -> {
            for (Slot candidate : ((AbstractContainerScreen<?>) shown(client)).getMenu().slots) {
                if (candidate.hasItem() && candidate.getItem().is(Items.IRON_INGOT)) {
                    return new Rect(
                            layout.inventory().x() + candidate.x,
                            layout.inventory().y() + candidate.y,
                            ICON,
                            ICON);
                }
            }
            throw new AssertionError("no slot holds the iron ingot");
        });
        // The page the catalogue was left on, so a wheel that turned it would show.
        context.runOnClient(client -> panel.state().turn(1, PAGES));
        setHidden(context, true);
        try {
            CatalogueScene.hover(context, slot);
            require(
                    context.computeOnClient(client -> panel.hoveredItem()) == null,
                    "hidden panels name no item under the cursor");
            for (int key : new int[] {InputConstants.KEY_R, InputConstants.KEY_U, InputConstants.KEY_A}) {
                context.getInput().pressKey(key);
                context.waitTicks(1);
            }
            require(
                    context.computeOnClient(client -> panel.state().lookup()) == null,
                    "R and U over a slot look nothing up while the panels are hidden");
            require(
                    CatalogueFavourites.shared().items(player).equals(starred),
                    "A over a slot stars nothing while the panels are hidden");
            require(hidden(context), "and the panels stay hidden");
            sdlShortcut(context, InputConstants.KEY_F);
            require(
                    !context.computeOnClient(client -> panel.searchFocused()),
                    "Ctrl+F does not focus the search of hidden panels");

            CatalogueScene.hover(context, layout.shelf().search());
            context.getInput().scroll(-1);
            context.waitTicks(1);
            require(
                    context.computeOnClient(client -> panel.state().page(PAGES)) == 1,
                    "the wheel over the hidden shelf turns no page");

            CatalogueScene.hover(context, CatalogueScene.gridCell(layout, 0));
            context.getInput().pressMouse(LEFT_MOUSE);
            CatalogueScene.hover(context, layout.shelf().craftable());
            context.getInput().pressMouse(LEFT_MOUSE);
            CatalogueScene.hover(context, layout.shelf().search());
            context.getInput().pressMouse(LEFT_MOUSE);
            context.waitTicks(1);
            require(
                    context.computeOnClient(client -> panel.state().lookup()) == null
                            && !context.computeOnClient(client -> panel.state().craftableOnly())
                            && !context.computeOnClient(client -> panel.searchFocused()),
                    "a click where a cell, the switch and the search would be does nothing while hidden");
            require(context.computeOnClient(client -> panel.state().page(PAGES)) == 1, "nor does it turn a page");
        } finally {
            setHidden(context, false);
            context.runOnClient(client -> panel.state().turn(-1, PAGES));
        }
    }

    /** Ctrl+F (Cmd+F on a Mac) as SDL delivers it: the runner's holdControl does not reach the key event's modifiers. */
    private static void sdlShortcut(ClientGameTestContext context, int key) {
        context.runOnClient(client -> {
            long window = client.getWindow().handle();
            KeyEvent event = new KeyEvent(
                    key,
                    SDLKeyboard.SDL_GetKeyFromScancode(key, (short) 0, false),
                    InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER);
            client.keyboardHandler.keyPress(window, InputConstants.PRESS, event);
            client.keyboardHandler.keyPress(window, InputConstants.RELEASE, event);
        });
        context.waitTicks(1);
    }

    private static void lookingAnItemUpBringsHiddenPanelsBack(ClientGameTestContext context) {
        CatalogueScene.openInventory(context);
        setHidden(context, true);
        context.runOnClient(client -> CatalogueScreens.open(DRILL));
        context.waitTicks(1);
        CataloguePanel panel = CatalogueScene.panel(context);
        require(!hidden(context), "asking for an item's recipe brings the hidden panels back");
        CatalogueState.Lookup lookup =
                context.computeOnClient(client -> panel.state().lookup());
        require(
                lookup != null && lookup.item() == DRILL && lookup.mode() == CatalogueState.Mode.RECIPES,
                "and shows that recipe, got " + lookup);
        require(context.computeOnClient(client -> panel.showsRecipePanel()), "the recipe panel is out");
        context.runOnClient(client -> panel.state().clear());
    }

    private static void aSpecSheetWorksWhileHidden(ClientGameTestContext context) {
        CatalogueScene.openInventory(context);
        CataloguePanel panel = CatalogueScene.panel(context);
        setHidden(context, true);
        try {
            context.runOnClient(client -> SpecSheetOpener.show(DRILL));
            context.waitTicks(2);
            require(
                    context.computeOnClient(client -> panel.sheetItem()) == DRILL,
                    "a spec sheet opens over the hidden panels");
            String title = DRILL.getDefaultInstance().getHoverName().getString().toUpperCase(Locale.ROOT);
            require(
                    context.computeOnClient(client -> DrawnText.of(shown(client)).stream()
                                    .anyMatch(drawn -> drawn.text().equals(title)))
                            && context.computeOnClient(client -> panel.sheetGlass()) != null,
                    "and draws its title '" + title + "'");
            CatalogueScene.hover(
                    context, Objects.requireNonNull(context.computeOnClient(client -> panel.sheetCloseSwitch())));
            RealInput.click(context, LEFT_MOUSE);
            require(
                    context.computeOnClient(client -> panel.sheetItem()) == null,
                    "the close switch puts the sheet away while the panels are hidden");
            require(hidden(context), "and the panels are still hidden after it");
            require(
                    context.computeOnClient(client -> DrawnText.of(shown(client)).stream()
                            .noneMatch(drawn -> drawn.text().equals(title))),
                    "the sheet is no longer drawn");
        } finally {
            setHidden(context, false);
        }
    }

    private static void theNarrowFallbackKeepsVanillasBookButton(ClientGameTestContext context) {
        CatalogueScene.openInventory(context);
        AbstractContainerScreen<?> screen =
                context.computeOnClient(client -> (AbstractContainerScreen<?>) shown(client));
        context.runOnClient(client -> screen.resize(NARROWEST - 1, HEIGHT));
        context.waitTicks(2);
        try {
            require(
                    context.computeOnClient(
                                    client -> CatalogueScene.panelNow(client).layout())
                            == null,
                    "the panels have no room at " + (NARROWEST - 1));
            require(
                    context.computeOnClient(client ->
                            Screens.getWidgets(screen).stream().noneMatch(CatalogueToggleButton.class::isInstance)),
                    "a window with no room for the panels has no catalogue button");
            List<AbstractWidget> book = context.computeOnClient(client -> Screens.getWidgets(screen).stream()
                    .filter(ImageButton.class::isInstance)
                    .toList());
            require(
                    !book.isEmpty() && book.stream().allMatch(button -> button.visible && button.active),
                    "and vanilla's recipe book button is still there to use");
        } finally {
            context.runOnClient(client -> screen.resize(WIDTH, HEIGHT));
            context.waitTicks(2);
        }
    }

    private static void theFurnaceHasTheButtonToo(ClientGameTestContext context) {
        context.setScreen(() -> null);
        context.runOnClient(client -> {
            Inventory inventory = ClientSession.player(client).getInventory();
            client.gui.setScreen(new FurnaceScreen(
                    new FurnaceMenu(0, inventory), inventory, Component.translatable("container.furnace")));
        });
        ClientWait.screen(context, FurnaceScreen.class);
        context.waitTicks(3);
        theButtonStandsClearAtEveryWindowSize(context, "the furnace");
        Rect button = rectOf(button(context));
        require(!hidden(context), "the furnace opens with the panels shown");
        click(context, button, LEFT_MOUSE);
        require(hidden(context), "the furnace's button puts the panels away");
        click(context, rectOf(button(context)), LEFT_MOUSE);
        require(!hidden(context), "and brings them back");
        context.setScreen(() -> null);
    }
}
