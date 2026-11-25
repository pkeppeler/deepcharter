package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Puts the parts catalogue beside the screens that have a vanilla recipe book: the player inventory, the crafting table, and the furnace,
 * blast furnace and smoker. Those are the screens whose recipe-book button the catalogue replaces, and each is vanilla's 176 by 166
 * image, which {@link CatalogueLayout} stands the panels beside. The recipe book is never shown: its button is hidden and the book is
 * closed on each open, so the screen is never moved aside for it and its inventory image is where the layout expects it.
 *
 * <p>All through Fabric's screen events, so no mixin: a panel is made when such a screen opens, draws after it, and takes the keys and
 * clicks that are its own before the screen sees them.
 */
public final class CatalogueScreens {
    private static final Map<Screen, CataloguePanel> PANELS = new HashMap<>();
    /** What every panel is set to: the client's, so it carries across a close and a reopen, and from the inventory to the table. */
    private static volatile CatalogueState state = new CatalogueState();
    /** An item {@link #open} was asked for before the inventory it needs was open, looked up as soon as the panel exists. */
    private static @Nullable Item pending;
    /** The screen each inventory {@link #open} made over another screen goes back to when it closes. */
    private static final Map<Screen, Screen> RETURN_TO = new HashMap<>();
    /** The screen to bring back on the next client tick, once the inventory it was under has closed. */
    private static @Nullable Screen reopen;

    private CatalogueScreens() {}

    public static void register() {
        CatalogueKeys.register();
        SpecSheetOpener.register();
        ClientTickEvents.END_CLIENT_TICK.register(CatalogueScreens::reopenReturnScreen);
        // A world is where the recipes were taught, so leaving it puts the catalogue back as a new client has it.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetState());
        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) -> {
            if (hasCatalogue(screen) && fits(width, height)) {
                closeVanillaBook(client);
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof AbstractRecipeBookScreen<?> book) {
                attach(book, width, height);
            }
        });
    }

    /** Puts the catalogue back as a new client has it: the next panel opens on an empty search, the switch off and nothing looked up. */
    public static void resetState() {
        state = new CatalogueState();
    }

    /** Whether a screen {@code width} by {@code height} has room for the panels, which is when they take the place of the vanilla recipe book. */
    private static boolean fits(int width, int height) {
        return CatalogueLayout.of(
                        width,
                        height,
                        CatalogueLook.current(),
                        CrtTuning.current(),
                        Minecraft.getInstance().font.lineHeight)
                != null;
    }

    /** Whether the catalogue stands beside {@code screen}: it is a vanilla screen with a recipe book. */
    public static boolean hasCatalogue(Screen screen) {
        return screen instanceof AbstractRecipeBookScreen<?>;
    }

    /** The catalogue beside {@code screen}, or null when it has none. */
    public static @Nullable CataloguePanel panelOf(Screen screen) {
        return PANELS.get(screen);
    }

    /**
     * Shows the recipes of {@code item}: what a click on an item of the quest map's index card calls, the "recipe one click away" link. Opens the inventory first when
     * no screen with a catalogue is open, over the screen that is open now, which comes back when the inventory closes (the handbook, with
     * its parent). A window too small for the consoles changes nothing. An item the charter knows no recipe of is shown as such, never its
     * recipes.
     *
     * <p>The screen it opens is always the survival {@link InventoryScreen}, so a creative player gets the survival inventory there.
     */
    public static void open(Item item) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null
                || !fits(
                        client.getWindow().getGuiScaledWidth(),
                        client.getWindow().getGuiScaledHeight())) {
            return;
        }
        Screen open = client.gui.screen();
        CataloguePanel panel = open == null ? null : panelOf(open);
        if (panel != null) {
            panel.lookUp(item, CatalogueState.Mode.RECIPES);
            return;
        }
        pending = item;
        // A vanilla screen, so it is not one of the mod's screens the layout gate asks to be covered by
        // ScreenLayoutClientTest.
        InventoryScreen inventory = new InventoryScreen(player);
        if (open != null) {
            RETURN_TO.put(inventory, open);
        }
        client.gui.setScreen(inventory);
        if (client.gui.screen() != inventory) {
            pending = null;
            RETURN_TO.remove(inventory);
        }
    }

    /** Brings back the screen an inventory of {@link #open} was over, unless something else has opened since. */
    private static void reopenReturnScreen(Minecraft client) {
        Screen back = reopen;
        if (back == null) {
            return;
        }
        reopen = null;
        if (client.player != null && client.gui.screen() == null) {
            client.gui.setScreen(back);
        }
    }

    private static void attach(AbstractRecipeBookScreen<?> screen, int width, int height) {
        CataloguePanel panel = PANELS.computeIfAbsent(screen, ignored -> new CataloguePanel(screen, state));
        panel.resize(width, height);
        // The panels replace the recipe book, so its button goes only while they are drawn: a window too small for them
        // keeps the book.
        if (panel.layout() != null) {
            hideBookButton(screen);
        }
        Item wanted = pending;
        if (wanted != null) {
            pending = null;
            panel.lookUp(wanted, CatalogueState.Mode.RECIPES);
        }
        // Fabric clears a screen's events when it is initialised again (a resize), so they are registered on every
        // init.
        // After the screen's contents and before its carried item and tooltips, so a stack on the cursor is over the
        // panels and a tooltip set
        // here (the hovered item's) is shown by the screen's own deferred tooltip step; AFTER_EXTRACT comes after that
        // step, too late for one.
        ScreenEvents.afterForeground(screen)
                .register((s, graphics, mouseX, mouseY, delta) -> panel.extract(graphics, mouseX, mouseY));
        ScreenEvents.afterTick(screen).register(s -> panel.tick());
        ScreenEvents.remove(screen).register(removed -> {
            PANELS.remove(removed);
            Screen back = RETURN_TO.remove(removed);
            if (back != null) {
                reopen = back;
            }
        });
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> !panel.keyPressed(event));
        ScreenKeyboardEvents.allowCharType(screen).register((s, event) -> !panel.charTyped(event));
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !panel.mouseClicked(event));
        ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> !panel.mouseReleased());
        ScreenMouseEvents.allowMouseScroll(screen)
                .register((s, x, y, horizontal, vertical) -> !panel.mouseScrolled(x, y, vertical));
    }

    /** The recipe-book button's size in vanilla's {@code AbstractRecipeBookScreen.initButton}: no other image button of these screens is that size. */
    private static final int BOOK_BUTTON_WIDTH = 20;

    private static final int BOOK_BUTTON_HEIGHT = 18;

    /** Hides the vanilla recipe-book button: it stays in the screen's lists and can neither be seen, hovered nor pressed. */
    private static void hideBookButton(Screen screen) {
        for (AbstractWidget widget : Screens.getWidgets(screen)) {
            if (widget instanceof ImageButton
                    && widget.getWidth() == BOOK_BUTTON_WIDTH
                    && widget.getHeight() == BOOK_BUTTON_HEIGHT) {
                widget.visible = false;
                widget.active = false;
            }
        }
    }

    /** Closes every recipe book of the player on this client, so a screen that opens never makes room for one the player cannot close. */
    private static void closeVanillaBook(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        for (RecipeBookType type : RecipeBookType.values()) {
            player.getRecipeBook().setOpen(type, false);
        }
    }
}
