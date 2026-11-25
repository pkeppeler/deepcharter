package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueKeys;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueScreens;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueToggleButton;
import io.github.pkeppeler.deepcharter.test.support.CatalogueFixtures;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ContainerScreenFields;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Client GameTest for #1282, the parts catalogue's show/hide button: it stands in the recipe-book button's place on the inventory and the
 * crafting table, clear of every slot and inside the screen at each window size; a click and the key (O unless rebound) put the panels
 * away and bring them back, but not while the search has the keyboard; its tooltip names the key bound now; and the choice carries from
 * one screen to the next.
 */
public class CatalogueToggleClientTest implements FabricClientGameTest {
    /** The game's left button: what a vanilla button answers to. */
    private static final int LEFT_MOUSE = InputConstants.MOUSE_BUTTON_LEFT;
    /** The button the parts catalogue's panels read as a plain click, for the search field. */
    private static final int PANEL_CLICK = 0;
    /** The default window, 854 by 480 at GUI scale 2. */
    private static final int WIDTH = 427;

    private static final int HEIGHT = 240;
    /** The narrowest GUI the panels draw at. */
    private static final int NARROWEST = 425;
    /** An item icon, and so a slot's hit area. */
    private static final int ICON = 16;

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
        click(context, search, PANEL_CLICK);
        require(context.computeOnClient(client -> panel.searchFocused()), "a click on the search field focuses it");
        context.getInput().typeChars("o");
        context.getInput().pressKey(InputConstants.KEY_O);
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
}
